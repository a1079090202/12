package com.example.dockyard.service;

import com.example.dockyard.domain.*;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.AppointmentRescheduleRepository;
import com.example.dockyard.repo.DockMaintenanceRepository;
import com.example.dockyard.repo.DockRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * 月台保养停用：登记停用窗、推导可用容量、逐条处置受影响预约（改期 / 改约待定）。
 *
 * 容量口径：预约只选月台类型、到场才派具体月台，因此某类型某槽的可约容量 =
 * 当时未停用的该类型活动月台数（全局固定容量 app.slot.capacity 已废弃）。
 *
 * 原预约内容不被覆盖：改期只更新 appointment 的时段，原时段 / 原因 / 操作人固化在
 * appointment_reschedule（只追加），并同步写 operation_event。
 */
@Service
public class DockMaintenanceService {

    private final DockMaintenanceRepository maintenances;
    private final DockRepository docks;
    private final AppointmentRepository appointments;
    private final AppointmentRescheduleRepository reschedules;
    private final EventLogService events;
    private final YardClock clock;
    private final SlotLock slotLock;

    @Value("${app.slot.length-minutes:30}")
    private int slotMinutes;

    public DockMaintenanceService(DockMaintenanceRepository maintenances, DockRepository docks,
                                  AppointmentRepository appointments,
                                  AppointmentRescheduleRepository reschedules,
                                  EventLogService events, YardClock clock, SlotLock slotLock) {
        this.maintenances = maintenances;
        this.docks = docks;
        this.appointments = appointments;
        this.reschedules = reschedules;
        this.events = events;
        this.clock = clock;
        this.slotLock = slotLock;
    }

    // --------------------------------------------------------------- 登记 / 取消

    @Transactional
    @PreAuthorize("hasRole('DISPATCHER')")
    public DockMaintenance register(Long dockId, LocalDate date, String startHM, String endHM,
                                    String reason, Long actorId) {
        if (reason == null || reason.strip().isBlank()) {
            throw new BusinessRuleException("停用必须写明原因");
        }
        Dock dock = docks.findById(dockId)
                .orElseThrow(() -> new BusinessRuleException("月台不存在"));
        if (!dock.isActive()) {
            throw new BusinessRuleException("月台 " + dock.getCode() + " 已停用，不能登记保养");
        }
        Instant start = clock.parseDateTime(date, startHM);
        Instant end = clock.parseDateTime(date, endHM);
        if (!end.isAfter(start)) {
            throw new BusinessRuleException("停用结束时间必须晚于开始时间");
        }
        if (!end.isAfter(clock.now())) {
            throw new BusinessRuleException("停用结束时间已过，不能登记过去的停用");
        }
        // 锁月台行串行化并发登记，避免两个调度同时给同一月台登记出重叠停用窗
        appointments.lockDockById(dockId);
        boolean overlap = maintenances.findActiveOverlapping(start, end).stream()
                .anyMatch(m -> m.getDockId().equals(dockId));
        if (overlap) {
            throw new BusinessRuleException(
                    "月台 " + dock.getCode() + " 在该时段已有生效中的停用登记，时间窗不能重叠");
        }

        DockMaintenance m = new DockMaintenance();
        m.setDockId(dockId);
        m.setWindowStart(start);
        m.setWindowEnd(end);
        m.setReason(reason.strip());
        m.setCreatedBy(actorId);
        return maintenances.save(m);
    }

    @Transactional
    @PreAuthorize("hasRole('DISPATCHER')")
    public void cancel(Long maintenanceId, String reason, Long actorId) {
        if (reason == null || reason.strip().isBlank()) {
            throw new BusinessRuleException("取消停用必须写明原因");
        }
        DockMaintenance m = maintenances.lockById(maintenanceId)
                .orElseThrow(() -> new BusinessRuleException("停用登记不存在"));
        if (!m.isScheduled()) {
            throw new BusinessRuleException("该停用登记已取消，不能重复取消");
        }
        m.setStatus("CANCELLED");
        m.setCancelledBy(actorId);
        m.setCancelledAt(clock.now());
        m.setCancelReason(reason.strip());
        maintenances.save(m);
        // 容量即时恢复；已标记改约待定的单不自动复活，仍需调度逐条改期
    }

    // --------------------------------------------------------------- 查询

    @Transactional(readOnly = true)
    public List<DockMaintenance> listAll() {
        return maintenances.findAllByOrderByWindowStartDesc();
    }

    @Transactional(readOnly = true)
    public DockMaintenance get(Long id) {
        return maintenances.findById(id)
                .orElseThrow(() -> new BusinessRuleException("停用登记不存在"));
    }

    /** 停用窗内、与该台同类型、尚未进场（仍可处置）的预约，按时段排序 */
    @Transactional(readOnly = true)
    public List<Appointment> impactList(DockMaintenance m) {
        DockType type = docks.findById(m.getDockId())
                .orElseThrow(() -> new BusinessRuleException("月台不存在"))
                .getDockType();
        return appointments.findBookedOverlapping(type, m.getWindowStart(), m.getWindowEnd());
    }

    /** 该停用已产生的处置记录（改期 / 待定），按操作时间排序 */
    @Transactional(readOnly = true)
    public List<AppointmentReschedule> actionsOf(Long maintenanceId) {
        return reschedules.findByMaintenanceIdOrderByActedAtAsc(maintenanceId);
    }

    /**
     * 已标记“改约待定”、仍未重新安排（当前状态 RESCHEDULE_PENDING）的预约：
     * 待定单已离开受影响清单，这里给调度一个补做改期的入口。
     */
    @Transactional(readOnly = true)
    public List<Appointment> pendingOf(DockMaintenance m) {
        List<Appointment> result = new ArrayList<>();
        for (AppointmentReschedule r : reschedules.findByMaintenanceIdOrderByActedAtAsc(m.getId())) {
            if (r.getAction() != RescheduleAction.PENDING) {
                continue;
            }
            appointments.findById(r.getAppointmentId())
                    .filter(a -> a.getStatus() == AppointmentStatus.RESCHEDULE_PENDING)
                    .ifPresent(result::add);
        }
        return result;
    }
    @Transactional(readOnly = true)
    public int availableDockCount(DockType type, Instant slotStart, Instant slotEnd) {
        int total = docks.findByActiveTrueAndDockTypeOrderByCode(type).size();
        Set<Long> blockedDocks = new HashSet<>();
        for (DockMaintenance m : maintenances.findActiveOverlapping(slotStart, slotEnd)) {
            blockedDocks.add(m.getDockId());
        }
        long blocked = docks.findAll().stream()
                .filter(d -> d.getDockType() == type && d.isActive() && blockedDocks.contains(d.getId()))
                .count();
        return Math.max(0, total - (int) blocked);
    }

    /** 当前时刻处于停用窗的月台 → 停用登记（看板标识用） */
    @Transactional(readOnly = true)
    public Map<Long, DockMaintenance> currentByDock(Instant at) {
        Map<Long, DockMaintenance> map = new HashMap<>();
        for (DockMaintenance m : maintenances.findActiveAt(at)) {
            map.putIfAbsent(m.getDockId(), m);
        }
        return map;
    }

    // --------------------------------------------------------------- 受影响单处置

    @Transactional
    @PreAuthorize("hasRole('DISPATCHER')")
    public void markPending(Long apptId, Long maintenanceId, String reason, Long actorId) {
        if (reason == null || reason.strip().isBlank()) {
            throw new BusinessRuleException("标记改约待定必须写明原因");
        }
        Appointment appt = lockAppt(apptId);
        requireBooked(appt);
        DockMaintenance m = mustBelongToImpact(apptId, maintenanceId);

        AppointmentReschedule rec = snapshot(appt, m, RescheduleAction.PENDING, reason, actorId);
        reschedules.save(rec);

        appt.setStatus(AppointmentStatus.RESCHEDULE_PENDING);
        appointments.save(appt);
        events.record(apptId, EventType.MAINT_PENDING, actorId, m.getDockId(),
                "月台保养停用（" + m.getReason() + "），标记改约待定；原时段 "
                        + clock.formatDateTime(appt.getSlotStart()) + "；处置原因：" + reason.strip());
    }

    @Transactional
    @PreAuthorize("hasRole('DISPATCHER')")
    public void reschedule(Long apptId, Long maintenanceId, LocalDate targetDate, String targetHM,
                           String reason, Long actorId) {
        if (reason == null || reason.strip().isBlank()) {
            throw new BusinessRuleException("改期必须写明原因");
        }
        Appointment appt = lockAppt(apptId);
        AppointmentStatus oldStatus = appt.getStatus();
        if (oldStatus != AppointmentStatus.BOOKED
                && oldStatus != AppointmentStatus.OVERRIDDEN
                && oldStatus != AppointmentStatus.RESCHEDULE_PENDING) {
            throw new BusinessRuleException("车辆已进入流程，不能改期");
        }
        DockMaintenance m = maintenanceId == null ? null
                : maintenances.lockById(maintenanceId).orElse(null);
        // 从停用详情发起的改期：BOOKED/OVERRIDDEN 单必须确实在该停用的受影响清单内；
        // 待定单已离开清单（靠 appointment_reschedule 关联），无需此校验
        if (m != null && oldStatus != AppointmentStatus.RESCHEDULE_PENDING) {
            boolean inImpact = impactList(m).stream().anyMatch(a -> a.getId().equals(apptId));
            if (!inImpact) {
                throw new BusinessRuleException("该预约不在此停用窗的受影响清单内");
            }
        }

        Instant targetStart = clock.truncateToSlot(
                clock.parseDateTime(targetDate, targetHM), slotMinutes);
        Instant targetEnd = targetStart.plusSeconds(slotMinutes * 60L);
        Instant currentSlot = clock.truncateToSlot(clock.now(), slotMinutes);
        if (targetStart.isBefore(currentSlot)) {
            throw new BusinessRuleException("不能改期到已经过去的时段");
        }

        // 与预约同一把槽位咨询锁：并发改期入同一目标槽时串行，后到者在锁内复查容量
        slotLock.lock(targetStart);
        int capacity = availableDockCount(appt.getDockType(), targetStart, targetEnd);
        if (capacity <= 0) {
            throw new BusinessRuleException("该时段没有可用的" + appt.getDockType().getLabel()
                    + "（可能正逢月台保养停用），无法改期");
        }
        long used = appointments.countActiveBySlotStartAndDockType(targetStart, appt.getDockType());
        if (used >= capacity) {
            throw new BusinessRuleException("目标时段（" + clock.formatTime(targetStart) + "）"
                    + appt.getDockType().getLabel() + "容量 " + capacity + " 已满，请改选其他时段");
        }

        Instant oldStart = appt.getSlotStart();
        Instant oldEnd = appt.getSlotEnd();
        AppointmentReschedule rec = snapshot(appt, m, RescheduleAction.RESCHEDULED, reason, actorId);
        rec.setNewSlotStart(targetStart);
        rec.setNewSlotEnd(targetEnd);
        reschedules.save(rec);

        appt.setSlotStart(targetStart);
        appt.setSlotEnd(targetEnd);
        // 待定单完成改期后回到正常已预约；插单标记（OVERRIDDEN）保留
        appt.setStatus(oldStatus == AppointmentStatus.RESCHEDULE_PENDING
                ? AppointmentStatus.BOOKED : oldStatus);
        appointments.save(appt);

        String maintText = m == null ? "" : "；停用原因：" + m.getReason();
        events.record(apptId, EventType.RESCHEDULED, actorId,
                m == null ? null : m.getDockId(),
                "保养改期：" + clock.formatDateTime(oldStart) + "–" + clock.formatTime(oldEnd)
                        + " → " + clock.formatDateTime(targetStart) + "–" + clock.formatTime(targetEnd)
                        + maintText + "；改期原因：" + reason.strip());
    }

    // --------------------------------------------------------------- 内部

    private Appointment lockAppt(Long apptId) {
        return appointments.lockById(apptId)
                .orElseThrow(() -> new BusinessRuleException("预约不存在"));
    }

    private void requireBooked(Appointment appt) {
        if (appt.getStatus() != AppointmentStatus.BOOKED
                && appt.getStatus() != AppointmentStatus.OVERRIDDEN) {
            throw new BusinessRuleException("只有尚未进场的预约能标记改约待定（当前："
                    + appt.getStatus().getLabel() + "）");
        }
    }

    /** 校验处置入口确实来自该停用窗的受影响清单，避免对任意单挂停用记录 */
    private DockMaintenance mustBelongToImpact(Long apptId, Long maintenanceId) {
        if (maintenanceId == null) {
            throw new BusinessRuleException("缺少停用登记");
        }
        DockMaintenance m = maintenances.lockById(maintenanceId)
                .orElseThrow(() -> new BusinessRuleException("停用登记不存在"));
        boolean inImpact = impactList(m).stream().anyMatch(a -> a.getId().equals(apptId));
        if (!inImpact) {
            throw new BusinessRuleException("该预约不在此停用窗的受影响清单内");
        }
        return m;
    }

    private AppointmentReschedule snapshot(Appointment appt, DockMaintenance m, RescheduleAction action,
                                           String reason, Long actorId) {
        AppointmentReschedule rec = new AppointmentReschedule();
        rec.setAppointmentId(appt.getId());
        rec.setMaintenanceId(m == null ? null : m.getId());
        rec.setAction(action);
        rec.setOriginalSlotStart(appt.getSlotStart());
        rec.setOriginalSlotEnd(appt.getSlotEnd());
        rec.setOriginalDockType(appt.getDockType());
        rec.setReason(reason.strip());
        rec.setActedBy(actorId);
        return rec;
    }
}
