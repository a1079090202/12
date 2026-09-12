package com.example.dockyard.service;

import com.example.dockyard.domain.*;
import com.example.dockyard.repo.AppointmentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 预约：30 分钟槽位容量拦截 + 调度员写明原因插单。
 * 接口层只收参数，排队/容量规则集中在这里。
 */
@Service
public class AppointmentService {

    private final AppointmentRepository appointments;
    private final EventLogService events;
    private final YardClock clock;
    private final EntityManager em;

    @Value("${app.slot.length-minutes:30}")
    private int slotMinutes;

    @Value("${app.slot.capacity:6}")
    private int slotCapacity;

    public AppointmentService(AppointmentRepository appointments, EventLogService events,
                              YardClock clock, EntityManager em) {
        this.appointments = appointments;
        this.events = events;
        this.clock = clock;
        this.em = em;
    }

    public record BookingRequest(String orderNo, String plateNo, String driverName, String driverPhone,
                                 String cargoType, DockType dockType,
                                 LocalDate slotDate, String slotTime /* HH:mm */) {}

    /**
     * 承运商提交预约。槽位按上海墙钟 30 分钟整点切分，
     * 同一槽位有效预约数（不含已取消）达到容量即拒绝。
     */
    @Transactional
    @PreAuthorize("hasRole('CARRIER')")
    public Appointment book(BookingRequest req, Long carrierId, Long actorId) {
        Instant slotStart = clock.truncateToSlot(
                clock.parseDateTime(req.slotDate(), req.slotTime()), slotMinutes);
        Instant slotEnd = slotStart.plusSeconds(slotMinutes * 60L);

        long used = appointments.countBySlotStartAndStatusNot(slotStart, AppointmentStatus.CANCELLED);
        if (used >= slotCapacity) {
            throw new BusinessRuleException(
                    "该时段（" + clock.formatTime(slotStart) + "）预约容量 " + slotCapacity
                            + " 已满，请更换时段或联系调度员插单");
        }

        Appointment appt = new Appointment();
        appt.setCode(nextCode(slotStart));
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
     * 调度员插单：绕过容量限制，但必须写明原因；原因与操作人留痕。
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

        long used = appointments.countBySlotStartAndStatusNot(slotStart, AppointmentStatus.CANCELLED);

        Appointment appt = new Appointment();
        appt.setCode(nextCode(slotStart));
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
                "插单原因：" + reason.strip() + "；插入时段已有 " + used + " 单，容量 " + slotCapacity);
        return appt;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('CARRIER','DISPATCHER')")
    public void cancel(Long apptId, Long actorId) {
        Appointment appt = appointments.findById(apptId)
                .orElseThrow(() -> new BusinessRuleException("预约不存在"));
        // 承运商只能取消本司预约；调度员可取消任意单
        var me = com.example.dockyard.security.CurrentUser.get();
        if (me.getRole() == Role.CARRIER && !appt.getCarrierId().equals(me.getCarrierId())) {
            throw new BusinessRuleException("只能取消本承运商的预约");
        }
        if (appt.getStatus() != AppointmentStatus.BOOKED && appt.getStatus() != AppointmentStatus.OVERRIDDEN) {
            throw new BusinessRuleException("车辆已进入流程，不能取消");
        }
        appt.setStatus(AppointmentStatus.CANCELLED);
        appointments.save(appt);
        events.record(apptId, EventType.BOOKED, actorId, null, "预约已取消");
    }

    public List<Appointment> daySchedule(LocalDate date) {
        return appointments.findBySlotBetween(clock.dayStart(date), clock.dayEnd(date));
    }

    public List<Appointment> listByCarrier(Long carrierId) {
        return appointments.findByCarrierIdOrderByCreatedAtDesc(carrierId);
    }

    /** 预约码：YYMMDD + 序列号，序列取自数据库，保证多人并发不重码 */
    private String nextCode(Instant slotStart) {
        String dayPart = java.time.LocalDate.ofInstant(slotStart, YardClock.ZONE)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
        Long seq = ((Number) em.createNativeQuery("select nextval('appt_code_seq')")
                .getSingleResult()).longValue();
        return "YY" + dayPart + String.format("%04d", seq % 10000);
    }

    private String normalizePlate(String plate) {
        return plate == null ? null : plate.strip().toUpperCase().replace(" ", "");
    }
}
