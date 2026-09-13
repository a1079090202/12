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
 *  - 晚于时段终点超过宽限（app.gate.late-tolerance-minutes，默认 45 分钟）-> 迟到标记（按上海时间口径）。
 */
@Service
public class GateService {

    private final AppointmentRepository appointments;
    private final EventLogService events;
    private final YardClock clock;

    /** 迟到宽限（分钟）：超过时段终点这么久算迟到；与容量/免费时长一样走统一配置 */
    @org.springframework.beans.factory.annotation.Value("${app.gate.late-tolerance-minutes:45}")
    private int lateToleranceMinutes;

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
        // 按预约码加悲观写锁：两个门卫同时扫同一码时在此串行，
        // 后到事务拿到锁后看到状态已推进为 GATED_IN，被下面的状态校验拒绝。
        Appointment appt = appointments.lockByCode(normalized)
                .orElseThrow(() -> new BusinessRuleException("预约码不存在：" + normalized));

        if (appt.getStatus() != AppointmentStatus.BOOKED && appt.getStatus() != AppointmentStatus.OVERRIDDEN) {
            if (appt.getStatus() == AppointmentStatus.RESCHEDULE_PENDING) {
                throw new BusinessRuleException("预约码 " + normalized
                        + " 已标记改约待定（月台保养停用），请联系调度员确认新时段后再进场");
            }
            throw new BusinessRuleException("预约码 " + normalized + " 已办理进场（当前："
                    + appt.getStatus().getLabel() + "），禁止重复进场");
        }

        // 同车在场预检（快速失败给出友好提示）。不同预约码但同车牌的并发进场锁的是
        // 不同预约行、互不可见，靠数据库 uq_appt_plate_in_yard 最终兜底（见下方 flush 捕获）。
        if (!appointments.findPlateInYard(appt.getPlateNo()).isEmpty()) {
            throw new BusinessRuleException("车牌 " + appt.getPlateNo() + " 已有车辆在场，禁止重复进场");
        }

        Instant now = clock.now();
        boolean early = now.isBefore(appt.getSlotStart());
        long lateMin = 0;
        boolean late = false;
        if (now.isAfter(appt.getSlotEnd().plus(lateToleranceMinutes, ChronoUnit.MINUTES))) {
            late = true;
            lateMin = ChronoUnit.MINUTES.between(appt.getSlotEnd(), now);
        }

        appt.setArrivedAt(now);
        appt.setEarlyArrival(early);
        appt.setLateFlag(late);
        appt.setLateMinutes((int) lateMin);
        appt.setStatus(AppointmentStatus.GATED_IN);
        appointments.save(appt);
        try {
            // 立即落库，让同车牌部分唯一索引在本事务内生效：
            // 不同预约码、同一车牌的并发进场在此撞索引，转成友好业务错误而不是 500。
            appointments.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            throw new BusinessRuleException(
                    "车牌 " + appt.getPlateNo() + " 已有车辆在场，禁止重复进场");
        }

        events.record(appt.getId(), EventType.GATE_IN, actorId, null,
                "门卫凭预约码 " + normalized + " 放行进场");
        if (early) {
            events.record(appt.getId(), EventType.EARLY_TO_WAITING, actorId, null,
                    "早于预约时段 " + clock.formatDateTime(appt.getSlotStart()) + "，先进入等候区");
        }
        if (late) {
            events.record(appt.getId(), EventType.LATE_FLAG, actorId, null,
                    "迟到 " + lateMin + " 分钟（超过宽限 " + lateToleranceMinutes + " 分钟）");
        }
        return new GateResult(appt, early, late, (int) lateMin);
    }
}
