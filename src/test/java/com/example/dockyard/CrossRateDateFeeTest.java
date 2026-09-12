package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.domain.DockType;
import com.example.dockyard.domain.FeeSegment;
import com.example.dockyard.domain.FeeSettlement;
import com.example.dockyard.repo.FeeSettlementRepository;
import com.example.dockyard.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景：计费等待跨上海自然日。
 * 进场 22:00（D 日），免费 90 分钟到 23:30，靠台 00:30（D+1 日）：
 *   等待 150 分钟、计费 60 分钟 = D 日 30 分 + D+1 日 30 分，
 *   D 日费率 120 元/时 -> 60.00，D+1 日 240 元/时 -> 120.00，合计 180.00。
 * 另验证：核费后再改当日费率，不影响已保存的费率快照。
 */
class CrossRateDateFeeTest extends AbstractIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired GateService gateService;
    @Autowired QueueAllocationService queue;
    @Autowired WarehouseOperationService warehouse;
    @Autowired FeeService feeService;
    @Autowired RateService rateService;
    @Autowired FeeSettlementRepository settlementRepository;
    @Autowired YardClock yardClock;

    private static final ZoneId ZONE = YardClock.ZONE;
    private final LocalDate day = LocalDate.now(ZONE).plusDays(14);

    @AfterEach
    void reset() {
        yardClock.setClock(Clock.system(ZONE));
    }

    private void freeze(LocalDate d, String hm) {
        yardClock.setClock(Clock.fixed(
                ZonedDateTime.of(d, LocalTime.parse(hm), ZONE).toInstant(), ZONE));
    }

    @Test
    void chargeable_wait_split_across_two_rate_dates_with_snapshots() {
        // 1) 配 D 日 / D+1 日费率
        loginAs("dispatcher");
        rateService.upsertRate(carrierIdOf("carrier1"), day, new BigDecimal("120.00"), userId("dispatcher"));
        rateService.upsertRate(carrierIdOf("carrier1"), day.plusDays(1), new BigDecimal("240.00"), userId("dispatcher"));

        // 2) 22:00 预约并进场
        freeze(day, "22:00");
        loginAs("carrier1");
        var req = new AppointmentService.BookingRequest("X-跨日", "测D001", "司机", null,
                "常温食品", DockType.STANDARD, day, "22:00");
        Appointment appt = appointmentService.book(req, carrierIdOf("carrier1"), userId("carrier1"));
        loginAs("guard");
        gateService.gateIn(appt.getCode(), userId("guard"));

        // 3) 调度派台（自动分配空闲的普通月台）
        loginAs("dispatcher");
        Appointment called = queue.callToDock(appt.getId(), null, userId("dispatcher"));
        Long dockId = called.getAssignedDockId();

        // 4) 次日 00:30 靠台：等待 150 分钟
        freeze(day.plusDays(1), "00:30");
        loginAs("warehouse");
        appt = warehouse.dock(called.getId(), dockId, userId("warehouse"));
        assertThat(appt.getWaitMinutes()).isEqualTo(150);

        FeeSettlement fs = feeService.settle(appt, userId("warehouse"));
        assertThat(fs.getWaitMinutes()).isEqualTo(150);
        assertThat(fs.getFreeMinutes()).isEqualTo(90);
        assertThat(fs.getChargeableMinutes()).isEqualTo(60);
        assertThat(fs.getOriginalAmount()).isEqualByComparingTo("180.00");
        assertThat(fs.getFinalAmount()).isEqualByComparingTo("180.00");
        assertThat(fs.getRateSnapshot()).isEqualByComparingTo("120.00");
        assertThat(fs.getRateDate()).isEqualTo(day);

        var segs = feeService.segments(fs.getId());
        assertThat(segs).hasSize(2);
        FeeSegment s1 = segs.get(0);
        FeeSegment s2 = segs.get(1);
        assertThat(s1.getSegmentDate()).isEqualTo(day);
        assertThat(s1.getMinutes()).isEqualTo(30);
        assertThat(s1.getRatePerHour()).isEqualByComparingTo("120.00");
        assertThat(s1.getAmount()).isEqualByComparingTo("60.00");
        assertThat(s2.getSegmentDate()).isEqualTo(day.plusDays(1));
        assertThat(s2.getMinutes()).isEqualTo(30);
        assertThat(s2.getRatePerHour()).isEqualByComparingTo("240.00");
        assertThat(s2.getAmount()).isEqualByComparingTo("120.00");

        // 5) 核费后把 D 日费率改成 999，历史单快照不变
        loginAs("dispatcher");
        rateService.upsertRate(carrierIdOf("carrier1"), day, new BigDecimal("999.00"), userId("dispatcher"));
        FeeSettlement reloaded = settlementRepository.findById(fs.getId()).orElseThrow();
        assertThat(reloaded.getOriginalAmount()).isEqualByComparingTo("180.00");
        assertThat(feeService.segments(fs.getId()).get(0).getRatePerHour())
                .isEqualByComparingTo("120.00");
    }

    @Test
    void wait_within_free_90_minutes_has_zero_fee_and_no_segments() {
        freeze(day, "10:00");
        loginAs("carrier1");
        var req = new AppointmentService.BookingRequest("X-免费", "测D002", "司机", null,
                "常温食品", DockType.STANDARD, day, "10:00");
        Appointment appt = appointmentService.book(req, carrierIdOf("carrier1"), userId("carrier1"));
        loginAs("guard");
        gateService.gateIn(appt.getCode(), userId("guard"));
        loginAs("dispatcher");
        Appointment called = queue.callToDock(appt.getId(), null, userId("dispatcher"));
        freeze(day, "11:00"); // 60 分钟 < 90
        loginAs("warehouse");
        appt = warehouse.dock(called.getId(), called.getAssignedDockId(), userId("warehouse"));

        FeeSettlement fs = feeService.settle(appt, userId("warehouse"));
        assertThat(fs.getChargeableMinutes()).isZero();
        assertThat(fs.getOriginalAmount()).isEqualByComparingTo("0.00");
        assertThat(feeService.segments(fs.getId())).isEmpty();
    }
}
