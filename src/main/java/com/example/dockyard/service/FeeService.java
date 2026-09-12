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
 *  - 等待时长 = 靠台时刻 - 进场时刻（秒级精度）；
 *  - 免费等候 90 分钟，超出部分才计费，起算点 = 进场 + 90 分钟；
 *  - 计费区间跨上海自然日时，按天分段，各段用承运商“当日费率”，
 *    每段费率写入 fee_segment 做快照，主快照费率取首个计费段；
 *  - 时长口径：分段先按秒切，各段向下取整后，用最大余数法把缺的整分钟
 *    分给余数秒最大的段（并列取最早段），保证各段整分钟之和 = 计费总分钟（向上取整），
 *    临界点（超出免费 1 秒）也计 1 分钟；
 *  - 金额全程 BigDecimal，每段按 2 位小数 HALF_UP 后求和；
 *  - 计费区间内任何一天缺当日费率一律拒绝核费（不设兜底费率），
 *    出场事务整体回滚；费率补齐后重新办理出场即可，不会产生错误金额单；
 *  - 原始金额 original_amount 一经生成不再改变（异议调整只改 final_amount）。
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

    /** 出场时调用：生成核费单与分段明细。重复调用安全（已存在则直接返回）。 */
    @Transactional
    public FeeSettlement settle(Appointment appt, Long actorId) {
        var existing = settlements.findByAppointmentId(appt.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant arrived = appt.getArrivedAt();
        Instant docked = appt.getDockedAt();
        // 展示/留痕口径的等待分钟（向下取整，与靠台时写入 appt.wait_minutes 一致）
        int waitMinutes = appt.getWaitMinutes() != null
                ? appt.getWaitMinutes()
                : (int) clock.minutesBetween(arrived, docked);

        Instant chargeStart = arrived.plusSeconds(freeMinutes * 60L);
        long chargeSeconds = Math.max(0, java.time.Duration.between(chargeStart, docked).getSeconds());
        // 计费总分钟向上取整：超出免费时长不足 1 分钟也计 1 分钟，杜绝临界少计
        int chargeable = chargeSeconds == 0 ? 0 : (int) ((chargeSeconds + 59) / 60);

        List<FeeSegment> segmentList = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        // 免费单无计费段：主费率记 0，费率日期记进场所在的上海自然日
        BigDecimal snapshotRate = BigDecimal.ZERO;
        LocalDate firstDate = clock.dateOf(arrived);

        if (chargeSeconds > 0) {
            // 1) 先按秒切出各上海自然日分段（此刻还不查费率）
            LocalDate d = clock.dateOf(chargeStart);
            LocalDate endDate = clock.dateOf(docked);
            record RawSegment(LocalDate date, long seconds) {}
            List<RawSegment> raw = new ArrayList<>();
            while (!d.isAfter(endDate)) {
                Instant segStart = maxInstant(chargeStart, clock.dayStart(d));
                Instant segEnd = minInstant(docked, clock.dayEnd(d));
                long secs = java.time.Duration.between(segStart, segEnd).getSeconds();
                if (secs > 0) {
                    raw.add(new RawSegment(d, secs));
                }
                d = d.plusDays(1);
            }

            // 2) 最大余数法分配整分钟：各段先向下取整，把总分钟向上取整后缺的分钟，
            //    依次分给余数秒最大的段（并列取最早段），保证各段分钟之和 = 计费总分钟
            long[] mins = new long[raw.size()];
            long floorSum = 0;
            List<Integer> byRemainder = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                mins[i] = raw.get(i).seconds() / 60;
                floorSum += mins[i];
                if (raw.get(i).seconds() % 60 > 0) {
                    byRemainder.add(i);
                }
            }
            byRemainder.sort((a, b) -> {
                long cmp = Long.compare(raw.get(b).seconds() % 60, raw.get(a).seconds() % 60);
                return cmp != 0 ? (int) cmp : Integer.compare(a, b);
            });
            long extra = chargeable - floorSum;
            for (int i = 0; i < extra && i < byRemainder.size(); i++) {
                mins[byRemainder.get(i)] += 1;
            }

            // 3) 只有最终计费分钟 > 0 的段才查当日费率并落明细快照
            //    （跨日尾部不足 1 分钟且未分到进位分钟的，不要求该日配费率）
            boolean firstCharged = true;
            for (int i = 0; i < raw.size(); i++) {
                if (mins[i] == 0) {
                    continue;
                }
                RawSegment r = raw.get(i);
                BigDecimal rate = rateFor(appt.getCarrierId(), r.date());
                BigDecimal amount = rate.multiply(BigDecimal.valueOf(mins[i]))
                        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
                segmentList.add(new FeeSegment(null, r.date(), (int) mins[i], rate, amount));
                total = total.add(amount);
                if (firstCharged) {
                    snapshotRate = rate;
                    firstDate = r.date();
                    firstCharged = false;
                }
            }
        }

        FeeSettlement fs = new FeeSettlement();
        fs.setAppointmentId(appt.getId());
        fs.setCarrierId(appt.getCarrierId());
        fs.setPlateNo(appt.getPlateNo());
        fs.setFreeMinutes(freeMinutes);
        fs.setWaitMinutes(waitMinutes);
        fs.setChargeableMinutes(chargeable);
        fs.setRateSnapshot(snapshotRate);
        fs.setRateDate(firstDate);
        fs.setOriginalAmount(total);
        fs.setFinalAmount(total);
        fs.setStatus(FeeStatus.CONFIRMED);
        fs.setGeneratedBy(actorId);
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

    /**
     * 承运商当日费率（上海自然日）。必须显式配置：缺失即视为业务数据不全，
     * 直接拒绝核费，绝不静默套用兜底单价。
     */
    @Transactional(readOnly = true)
    public BigDecimal rateFor(Long carrierId, LocalDate date) {
        return rates.findByCarrierIdAndRateDate(carrierId, date)
                .map(CarrierDailyRate::getRatePerHour)
                .orElseThrow(() -> new BusinessRuleException(
                        "承运商在 " + date + " 的当日费率未配置，无法核费；"
                                + "请调度员先在「费率配置」中补齐该日费率，再重新办理出场"));
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

    /**
     * 上海自然日内生成的核费单（用于首页与下载明细）。
     * 注意口径：按 generated_at（车辆出场、核费单生成的时刻）归属自然日，
     * 跨午夜作业计入出场日；计费分钟本身仍按作业经过的各费率日分段。
     */
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
