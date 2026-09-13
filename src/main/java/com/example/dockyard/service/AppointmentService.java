package com.example.dockyard.service;

import com.example.dockyard.domain.*;
import com.example.dockyard.repo.AppointmentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 预约：槽位容量拦截（槽长 app.slot.length-minutes 可配）+ 调度员写明原因插单。
 * 容量不再是固定配置：某类型某 30 分钟槽的容量 = 当时可用（未停用）的该类型月台数，
 * 由 {@link DockMaintenanceService#availableDockCount} 统一推导，保养停用自动扣减。
 * 接口层只收参数，排队/容量规则集中在这里。
 */
@Service
public class AppointmentService {

    private final AppointmentRepository appointments;
    private final EventLogService events;
    private final YardClock clock;
    private final AppointmentCodeGenerator codeGenerator;
    private final SlotLock slotLock;
    private final DockMaintenanceService maintenance;

    @Value("${app.slot.length-minutes:30}")
    private int slotMinutes;

    public AppointmentService(AppointmentRepository appointments, EventLogService events,
                              YardClock clock, AppointmentCodeGenerator codeGenerator,
                              SlotLock slotLock, DockMaintenanceService maintenance) {
        this.appointments = appointments;
        this.events = events;
        this.clock = clock;
        this.codeGenerator = codeGenerator;
        this.slotLock = slotLock;
        this.maintenance = maintenance;
    }

    public record BookingRequest(String orderNo, String plateNo, String driverName, String driverPhone,
                                 String cargoType, DockType dockType,
                                 LocalDate slotDate, String slotTime /* HH:mm */) {}

    /**
     * 承运商提交预约。槽位按上海墙钟整点切分（槽长 app.slot.length-minutes），
     * 同一槽位、同类型的有效预约数（不含已取消/改约待定）达到可用月台容量即拒绝。
     *
     * 并发安全：先对 slotStart 取事务级咨询锁，把同一槽位的并发提交串行化——
     * 后到事务在锁上等待，拿到锁后重新计数，必然看到先到事务已提交的预约，
     * 从而干净地拒绝超额（消除“先计数后插入”的 TOCTOU 超卖竞态）。
     */
    @Transactional
    @PreAuthorize("hasRole('CARRIER')")
    public Appointment book(BookingRequest req, Long carrierId, Long actorId) {
        Instant slotStart = clock.truncateToSlot(
                clock.parseDateTime(req.slotDate(), req.slotTime()), slotMinutes);
        Instant slotEnd = slotStart.plusSeconds(slotMinutes * 60L);

        slotLock.lock(slotStart);
        int capacity = maintenance.availableDockCount(req.dockType(), slotStart, slotEnd);
        if (capacity <= 0) {
            throw new BusinessRuleException("该时段没有可用的" + req.dockType().getLabel()
                    + "（月台保养停用），请更换时段或联系调度员");
        }
        long used = appointments.countActiveBySlotStartAndDockType(slotStart, req.dockType());
        if (used >= capacity) {
            throw new BusinessRuleException(
                    "该时段（" + clock.formatTime(slotStart) + "）" + req.dockType().getLabel()
                            + "容量 " + capacity + " 已满，请更换时段或联系调度员插单");
        }

        Appointment appt = new Appointment();
        appt.setCode(codeGenerator.next(slotStart));
        appt.setCarrierId(carrierId);
        appt.setOrderNo(req.orderNo());
        appt.setPlateNo(normalizePlate(req.plateNo()));
        appt.setDriverName(req.driverName());
        appt.setDriverPhone(req.driverPhone());
        appt.setCargoType(req.cargoType());
        appt.setDockType(req.dockType());
        appt.setSlotStart(slotStart);
        appt.setSlotEnd(slotEnd);
        appt.setStatus(AppointmentStatus.BOOKED);
        appt.setCreatedBy(actorId);
        appointments.save(appt);
        events.record(appt.getId(), EventType.BOOKED, actorId, null,
                "预约 " + req.dockType().getLabel() + "，时段 " + clock.formatDateTime(slotStart));
        return appt;
    }

    /**
     * 调度员插单：可超过容量上限，但必须写明原因；该类型可用月台为 0（全部保养停用）时
     * 物理上无台可派，连插单也拒绝。原因与操作人留痕。
     * 同样先取槽位咨询锁，保证“计数（写进留痕文案）+ 插入”相对并发提交是串行一致的。
     */
    @Transactional
    @PreAuthorize("hasRole('DISPATCHER')")
    public Appointment override(BookingRequest req, Long carrierId, Long actorId, String reason) {
        if (reason == null || reason.strip().isBlank()) {
            throw new BusinessRuleException("插单必须写明原因");
        }
        Instant slotStart = clock.truncateToSlot(
                clock.parseDateTime(req.slotDate(), req.slotTime()), slotMinutes);
        Instant slotEnd = slotStart.plusSeconds(slotMinutes * 60L);

        slotLock.lock(slotStart);
        int capacity = maintenance.availableDockCount(req.dockType(), slotStart, slotEnd);
        if (capacity <= 0) {
            throw new BusinessRuleException("该时段没有可用的" + req.dockType().getLabel()
                    + "（月台保养停用），无法插单");
        }
        long used = appointments.countActiveBySlotStartAndDockType(slotStart, req.dockType());

        Appointment appt = new Appointment();
        appt.setCode(codeGenerator.next(slotStart));
        appt.setCarrierId(carrierId);
        appt.setOrderNo(req.orderNo());
        appt.setPlateNo(normalizePlate(req.plateNo()));
        appt.setDriverName(req.driverName());
        appt.setDriverPhone(req.driverPhone());
        appt.setCargoType(req.cargoType());
        appt.setDockType(req.dockType());
        appt.setSlotStart(slotStart);
        appt.setSlotEnd(slotEnd);
        appt.setStatus(AppointmentStatus.OVERRIDDEN);
        appt.setOverrideReason(reason.strip());
        appt.setOverriddenBy(actorId);
        appt.setCreatedBy(actorId);
        appointments.save(appt);
        events.record(appt.getId(), EventType.OVERRIDE, actorId, null,
                "插单原因：" + reason.strip() + "；插入时段已有 " + used + " 单，可用容量 " + capacity);
        return appt;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('CARRIER','DISPATCHER')")
    public void cancel(Long apptId, Long actorId) {
        // 行锁串行化：与门卫进场在同一预约行上互斥，杜绝“边进场边取消”。
        // 状态判断在锁内进行，两个并发取消也只会有一个成功。
        Appointment appt = appointments.lockById(apptId)
                .orElseThrow(() -> new BusinessRuleException("预约不存在"));
        // 承运商只能取消本司预约；调度员可取消任意单
        var me = com.example.dockyard.security.CurrentUser.get();
        if (me.getRole() == Role.CARRIER && !appt.getCarrierId().equals(me.getCarrierId())) {
            throw new ForbiddenException("只能取消本承运商的预约");
        }
        // 改约待定不占容量，允许承运商取消；已进入场流程的不能取消
        if (appt.getStatus() != AppointmentStatus.BOOKED
                && appt.getStatus() != AppointmentStatus.OVERRIDDEN
                && appt.getStatus() != AppointmentStatus.RESCHEDULE_PENDING) {
            throw new BusinessRuleException("车辆已进入流程，不能取消");
        }
        appt.setStatus(AppointmentStatus.CANCELLED);
        appointments.save(appt);
        events.record(apptId, EventType.CANCELLED, actorId, null, "预约已取消");
    }

    public List<Appointment> daySchedule(LocalDate date) {
        return appointments.findBySlotBetween(clock.dayStart(date), clock.dayEnd(date));
    }

    public List<Appointment> listByCarrier(Long carrierId) {
        return appointments.findByCarrierIdOrderByCreatedAtDesc(carrierId);
    }

    private String normalizePlate(String plate) {
        return plate == null ? null : plate.strip().toUpperCase().replace(" ", "");
    }
}
