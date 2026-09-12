package com.example.dockyard.service;

import com.example.dockyard.domain.*;
import com.example.dockyard.repo.AppointmentRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 门卫进场：凭预约码放行。
 *  - 同一预约码只能进场一次（状态推进后再次使用直接拒绝）；
 *  - 同一车牌已有车辆在场时拒绝（防止一张车重复进场/重复靠台的第一道关）；
 *  - 早于时段起点进场 -> 进入等候区并标记；
 *  - 晚于时段终点 45 分钟 -> 迟到标记（按上海时间口径）。
 */
@Service
public class GateService {

    /** 迟到宽限：超过时段终点 45 分钟算迟到 */
    public static final int LATE_TOLERANCE_MINUTES = 45;

    private final AppointmentRepository appointments;
    private final EventLogService events;
    private final YardClock clock;

    public GateService(AppointmentRepository appointments, EventLogService events, YardClock clock) {
        this.appointments = appointments;
        this.events = events;
        this.clock = clock;
    }

    public record GateResult(Appointment appointment, boolean early, boolean late, int lateMinutes) {}

    @Transactional
    @PreAuthorize("hasRole('GUARD')")
    public GateResult gateIn(String code, Long actorId) {
        String normalized = code == null ? "" : code.strip().toUpperCase();
        // 行锁串行化：两个门卫同时扫同一预约码时，后者在锁上等待，拿到后看到状态已推进
        Appointment appt = appointments.lockByCode(normalized)
                .orElseThrow(() -> new BusinessRuleException("预约码不存在：" + normalized));

        if (appt.getStatus() != AppointmentStatus.BOOKED && appt.getStatus() != AppointmentStatus.OVERRIDDEN) {
            throw new BusinessRuleException("预约码 " + normalized + " 已办理进场（当前："
                    + appt.getStatus().getLabel() + "），禁止重复进场");
        }

        // 同车在场检查（数据库 uq_appt_plate_in_yard 也会兜底）
        if (!appointments.findPlateInYard(appt.getPlateNo()).isEmpty()) {
            throw new BusinessRuleException("车牌 " + appt.getPlateNo() + " 已有车辆在场，禁止重复进场");
        }

        Instant now = clock.now();
        boolean early = now.isBefore(appt.getSlotStart());
        long lateMin = 0;
        boolean late = false;
        if (now.isAfter(appt.getSlotEnd().plus(LATE_TOLERANCE_MINUTES, ChronoUnit.MINUTES))) {
            late = true;
            lateMin = ChronoUnit.MINUTES.between(appt.getSlotEnd(), now);
        }

        appt.setArrivedAt(now);
        appt.setEarlyArrival(early);
        appt.setLateFlag(late);
        appt.setLateMinutes((int) lateMin);
        appt.setStatus(AppointmentStatus.GATED_IN);
        appt.setUpdatedAt(now);
        appointments.save(appt);

        events.record(appt.getId(), EventType.GATE_IN, actorId, null,
                "门卫凭预约码 " + normalized + " 放行进场");
        if (early) {
            events.record(appt.getId(), EventType.EARLY_TO_WAITING, actorId, null,
                    "早于预约时段 " + clock.formatDateTime(appt.getSlotStart()) + "，先进入等候区");
        }
        if (late) {
            events.record(appt.getId(), EventType.LATE_FLAG, actorId, null,
                    "迟到 " + lateMin + " 分钟（超过宽限 " + LATE_TOLERANCE_MINUTES + " 分钟）");
        }
        return new GateResult(appt, early, late, (int) lateMin);
    }
}
