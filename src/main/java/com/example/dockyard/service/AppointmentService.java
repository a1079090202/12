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
    private final AppointmentCodeGenerator codeGenerator;

    @Value("${app.slot.length-minutes:30}")
    private int slotMinutes;

    @Value("${app.slot.capacity:6}")
    private int slotCapacity;

    public AppointmentService(AppointmentRepository appointments, EventLogService events,
                              YardClock clock, EntityManager em, AppointmentCodeGenerator codeGenerator) {
        this.appointments = appointments;
        this.events = events;
        this.clock = clock;
        this.em = em;
        this.codeGenerator = codeGenerator;
    }

    public record BookingRequest(String orderNo, String plateNo, String driverName, String driverPhone,
                                 String cargoType, DockType dockType,
                                 LocalDate slotDate, String slotTime /* HH:mm */) {}

    /**
     * 承运商提交预约。槽位按上海墙钟 30 分钟整点切分，
     * 同一槽位有效预约数（不含已取消）达到容量即拒绝。
     *
     * 并发安全：用事务级咨询锁把同一 slotStart 的并发提交串行化——
     * 第二个事务在锁上等待，拿到锁后重新计数，必然看到第一个事务已提交的单，
     * 从而干净地拒绝超额（消除“先计数后插入”的 TOCTOU 超额竞态）。
     */
    @Transactional
    @PreAuthorize("hasRole('CARRIER')")
    public Appointment book(BookingRequest req, Long carrierId, Long actorId) {
        Instant slotStart = clock.truncateToSlot(
                clock.parseDateTime(req.slotDate(), req.slotTime()), slotMinutes);
        Instant slotEnd = slotStart.plusSeconds(slotMinutes * 60L);

        lockSlot(slotStart);
        long used = appointments.countBySlotStartAndStatusNot(slotStart, AppointmentStatus.CANCELLED);
        if (used >= slotCapacity) {
            throw new BusinessRuleException(
                    "该时段（" + clock.formatTime(slotStart) + "）预约容量 " + slotCapacity
                            + " 已满，请更换时段或联系调度员插单");
        }

        Instant now = clock.now();
        Appointment appt = baseAppointment(req, carrierId, slotStart, slotEnd);
        appt.setStatus(AppointmentStatus.BOOKED);
        appt.setCreatedBy(actorId);
        appt.setCreatedAt(now);
        appt.setUpdatedAt(now);
        appointments.save(appt);
        events.record(appt.getId(), EventType.BOOKED, actorId, null,
                "预约 " + req.dockType().getLabel() + "，时段 " + clock.formatDateTime(slotStart));
        return appt;
    }

    /**
     * 调度员插单：绕过容量限制，但必须写明原因；原因与操作人留痕。
     * 同样先取 slot 咨询锁，保证计数（写入留痕文案）与插入相对并发提交是串行一致的。
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

        lockSlot(slotStart);
        long used = appointments.countBySlotStartAndStatusNot(slotStart, AppointmentStatus.CANCELLED);

        Instant now = clock.now();
        Appointment appt = baseAppointment(req, carrierId, slotStart, slotEnd);
        appt.setStatus(AppointmentStatus.OVERRIDDEN);
        appt.setOverrideReason(reason.strip());
        appt.setOverriddenBy(actorId);
        appt.setCreatedBy(actorId);
        appt.setCreatedAt(now);
        appt.setUpdatedAt(now);
        appointments.save(appt);
        events.record(appt.getId(), EventType.OVERRIDE, actorId, null,
                "插单原因：" + reason.strip() + "；插入时段已有 " + used + " 单，容量 " + slotCapacity);
        return appt;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('CARRIER','DISPATCHER')")
    public void cancel(Long apptId, Long actorId) {
        // 行锁串行化：与门卫进场在同一预约行上互斥，杜绝“边进场边取消”
        Appointment appt = appointments.lockById(apptId)
                .orElseThrow(() -> new BusinessRuleException("预约不存在"));
        // 承运商只能取消本司预约；调度员可取消任意单
        var me = com.example.dockyard.security.CurrentUser.get();
        if (me.getRole() == Role.CARRIER && !appt.getCarrierId().equals(me.getCarrierId())) {
            throw new org.springframework.security.access.AccessDeniedException("只能取消本承运商的预约");
        }
        if (appt.getStatus() != AppointmentStatus.BOOKED && appt.getStatus() != AppointmentStatus.OVERRIDDEN) {
            throw new BusinessRuleException("车辆已进入流程，不能取消");
        }
        appt.setStatus(AppointmentStatus.CANCELLED);
        appt.setUpdatedAt(clock.now());
        appointments.save(appt);
        events.record(apptId, EventType.CANCELLED, actorId, null, "预约已取消");
    }

    public List<Appointment> daySchedule(LocalDate date) {
        return appointments.findBySlotBetween(clock.dayStart(date), clock.dayEnd(date));
    }

    public List<Appointment> listByCarrier(Long carrierId) {
        return appointments.findByCarrierIdOrderByCreatedAtDesc(carrierId);
    }

    /** 同 slotStart 的并发预约串行化：key 取槽起点 epoch 秒（全局唯一的槽标识） */
    private void lockSlot(Instant slotStart) {
        em.createNativeQuery("select pg_advisory_xact_lock(:key)")
                .setParameter("key", slotStart.getEpochSecond())
                .getSingleResult();
    }

    private Appointment baseAppointment(BookingRequest req, Long carrierId,
                                        Instant slotStart, Instant slotEnd) {
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
        return appt;
    }

    private String normalizePlate(String plate) {
        return plate == null ? null : plate.strip().toUpperCase().replace(" ", "");
    }
}
