package com.example.dockyard.service;

import com.example.dockyard.domain.*;
import com.example.dockyard.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 核费（独立服务，只负责金额）：
 *  - 等待时长 = 靠台时刻 - 进场时刻；
 *  - 免费等候 90 分钟，超出部分才计费，起算点 = 进场 + 90 分钟；
 *  - 计费区间跨上海自然日时，按天分段，各段用承运商“当日费率”，
 *    每段费率写入 fee_segment 做快照，主快照费率取首段；
 *  - 金额全程 BigDecimal，按 2 位小数 HALF_UP；
 *  - 原始金额 original_amount 一经生成不再改变（异议调整只改 final_amount）。
 *  - 并发安全：settle 先对预约行加悲观锁再查是否已核费，重复/并发调用幂等；
 *    计费日缺费率直接抛业务异常（出场事务回滚），绝不按默认费率静默错误结算。
 */
@Service
public class FeeService {

    private final FeeSettlementRepository settlements;
    private final FeeSegmentRepository segments;
    private final CarrierDailyRateRepository rates;
    private final AppointmentRepository appointments;
    private final EventLogService events;
    private final YardClock clock;

    @Value("${app.fee.free-minutes:90}")
    private int freeMinutes;

    public FeeService(FeeSettlementRepository settlements, FeeSegmentRepository segments,
                      CarrierDailyRateRepository rates, AppointmentRepository appointments,
                      EventLogService events, YardClock clock) {
        this.settlements = settlements;
        this.segments = segments;
        this.rates = rates;
        this.appointments = appointments;
        this.events = events;
        this.clock = clock;
    }

    /** 核费明细下载用的预约简要信息 */
    public record AppointmentBrief(String code, java.time.Instant arrivedAt, java.time.Instant dockedAt) {}

    @Transactional(readOnly = true)
    public AppointmentBrief appointmentBrief(Long appointmentId) {
        var a = appointments.findById(appointmentId)
                .orElseThrow(() -> new BusinessRuleException("预约不存在：" + appointmentId));
        return new AppointmentBrief(a.getCode(), a.getArrivedAt(), a.getDockedAt());
    }

    /**
     * 出场时调用：生成核费单与分段明细。幂等（重复/并发调用返回已存在的同一张单）。
     * 缺当日费率时抛 BusinessRuleException：由出场事务整体回滚，车辆停在“卸货完成”，
     * 调度补配费率后可重新点出场。
     */
    @Transactional
    public FeeSettlement settle(Appointment appt, Long actorId) {
        // 与出场同一把预约行锁：串行化并发/重复核费，锁内复查保证只生成一张单
        appointments.lockById(appt.getId());
        var existing = settlements.findByAppointmentId(appt.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant arrived = appt.getArrivedAt();
        Instant docked = appt.getDockedAt();
        int waitMinutes = appt.getWaitMinutes() != null
                ? appt.getWaitMinutes()
                : (int) clock.minutesBetween(arrived, docked);
        int chargeable = Math.max(0, waitMinutes - freeMinutes);

        List<FeeSegment> segmentList = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        LocalDate rateDate = clock.dateOf(arrived);
        BigDecimal snapshotRate = null;

        if (chargeable > 0) {
            Instant chargeStart = arrived.plusSeconds(freeMinutes * 60L);
            LocalDate d = clock.dateOf(chargeStart);
            LocalDate endDate = clock.dateOf(docked);
            boolean first = true;
            while (!d.isAfter(endDate)) {
                Instant segStart = maxInstant(chargeStart, clock.dayStart(d));
                Instant segEnd = minInstant(docked, clock.dayEnd(d));
                if (segEnd.isAfter(segStart)) {
                    int mins = (int) clock.minutesBetween(segStart, segEnd);
                    if (mins > 0) {
                        final LocalDate segDate = d;
                        // 计费日必须显式配置费率，缺失即硬失败，杜绝按默认价错误结算
                        BigDecimal rate = rates.findByCarrierIdAndRateDate(appt.getCarrierId(), segDate)
                                .map(CarrierDailyRate::getRatePerHour)
                                .orElseThrow(() -> new BusinessRuleException(
                                        "承运商在 " + segDate + " 未配置费率，无法核费；请调度在费率管理中补配后重新出场"));
                        BigDecimal amount = rate.multiply(BigDecimal.valueOf(mins))
                                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
                        segmentList.add(new FeeSegment(null, d, mins, rate, amount));
                        total = total.add(amount);
                        if (first) {
                            snapshotRate = rate;
                            rateDate = d;
                            first = false;
                        }
                    }
                }
                d = d.plusDays(1);
            }
        }

        // 0 计费（免费时长内）不要求费率；快照列 not null，取进场当日已配费率，缺则记 0（金额本身为 0）
        if (snapshotRate == null) {
            snapshotRate = rates.findByCarrierIdAndRateDate(appt.getCarrierId(), clock.dateOf(arrived))
                    .map(CarrierDailyRate::getRatePerHour)
                    .orElse(BigDecimal.ZERO);
        }

        FeeSettlement fs = new FeeSettlement();
        fs.setAppointmentId(appt.getId());
        fs.setCarrierId(appt.getCarrierId());
        fs.setPlateNo(appt.getPlateNo());
        fs.setFreeMinutes(freeMinutes);
        fs.setWaitMinutes(waitMinutes);
        fs.setChargeableMinutes(chargeable);
        fs.setRateSnapshot(snapshotRate);
        fs.setRateDate(rateDate);
        fs.setOriginalAmount(total);
        fs.setFinalAmount(total);
        fs.setStatus(FeeStatus.CONFIRMED);
        fs.setGeneratedBy(actorId);
        fs.setGeneratedAt(clock.now());
        settlements.save(fs);

        for (FeeSegment s : segmentList) {
            segments.save(new FeeSegment(fs.getId(), s.getSegmentDate(), s.getMinutes(),
                    s.getRatePerHour(), s.getAmount()));
        }

        events.record(appt.getId(), EventType.FEE_SETTLED, actorId, appt.getAssignedDockId(),
                "等待 " + waitMinutes + " 分钟，免费 " + freeMinutes + " 分钟，计费 "
                        + chargeable + " 分钟，核费 " + total + " 元");
        return fs;
    }

    /** 承运商当日费率（上海自然日）；未配置返回 empty，由调用方决定硬失败还是取 0 */
    @Transactional(readOnly = true)
    public java.util.Optional<BigDecimal> rateFor(Long carrierId, LocalDate date) {
        return rates.findByCarrierIdAndRateDate(carrierId, date)
                .map(CarrierDailyRate::getRatePerHour);
    }

    @Transactional(readOnly = true)
    public FeeSettlement getByAppointment(Long appointmentId) {
        return settlements.findByAppointmentId(appointmentId)
                .orElseThrow(() -> new BusinessRuleException("该预约尚未生成核费单（车辆可能还未出场）"));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<FeeSettlement> peekByAppointment(Long appointmentId) {
        return settlements.findByAppointmentId(appointmentId);
    }

    @Transactional(readOnly = true)
    public List<FeeSegment> segments(Long settlementId) {
        return segments.findBySettlementIdOrderBySegmentDateAsc(settlementId);
    }

    /** 上海自然日内核费单（用于首页与下载明细） */
    @Transactional(readOnly = true)
    public List<FeeSettlement> settlementsOfDay(LocalDate date) {
        return settlements.findByGeneratedAtBetweenOrderByGeneratedAtAsc(
                clock.dayStart(date), clock.dayEnd(date));
    }

    private Instant maxInstant(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private Instant minInstant(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
