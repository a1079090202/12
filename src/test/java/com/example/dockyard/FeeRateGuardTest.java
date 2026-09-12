package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.domain.AppointmentStatus;
import com.example.dockyard.domain.DockType;
import com.example.dockyard.domain.FeeSettlement;
import com.example.dockyard.repo.AppointmentRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 费率配置与计费时钟口径回归：
 *  - 计费当日缺费率：出场核费整体拒绝（事务回滚，车不出场、无核费单），
 *    补齐费率后重新出场成功，绝不按默认 60 元静默结算；
 *  - 秒级边界：超出免费 30 秒计 1 分钟，免费时长内 0 元且不需要费率；
 *  - 冻结时钟下 generated_at 与业务自然日一致（审计时间戳走 YardClock）。
 */
class FeeRateGuardTest extends AbstractIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired GateService gateService;
    @Autowired QueueAllocationService queue;
    @Autowired WarehouseOperationService warehouse;
    @Autowired FeeService feeService;
    @Autowired RateService rateService;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired FeeSettlementRepository settlementRepository;
    @Autowired YardClock yardClock;

    private static final ZoneId ZONE = YardClock.ZONE;
    private final LocalDate day = LocalDate.now(ZONE).plusDays(21);

    @AfterEach
    void reset() {
        yardClock.setClock(Clock.system(ZONE));
    }

    private void freeze(LocalDate d, int hh, int mm, int ss) {
        yardClock.setClock(Clock.fixed(
                ZonedDateTime.of(d, LocalTime.of(hh, mm, ss), ZONE).toInstant(), ZONE));
    }

    private Appointment readyForExit(String orderNo, LocalDate d, String dockHms) {
        // 进场前冻结到预约日 10:00:00，保证等待区间从该时刻起算
        freeze(d, 10, 0, 0);
        loginAs("carrier1");
        var req = new AppointmentService.BookingRequest(orderNo, "测R" + orderNo.hashCode(), "司机",
                null, "常温食品", DockType.STANDARD, d, "10:00");
        Appointment appt = appointmentService.book(req, carrierIdOf("carrier1"), userId("carrier1"));
        loginAs("guard");
        gateService.gateIn(appt.getCode(), userId("guard"));
        loginAs("dispatcher");
        appt = queue.callToDock(appt.getId(), null, userId("dispatcher"));
        loginAs("warehouse");
        String[] hms = dockHms.split(":");
        freeze(d, Integer.parseInt(hms[0]), Integer.parseInt(hms[1]),
                hms.length > 2 ? Integer.parseInt(hms[2]) : 0);
        appt = warehouse.dock(appt.getId(), appt.getAssignedDockId(), userId("warehouse"));
        warehouse.startUnload(appt.getId(), userId("warehouse"));
        warehouse.complete(appt.getId(), userId("warehouse"));
        return appointmentRepository.findById(appt.getId()).orElseThrow();
    }

    @Test
    void missing_rate_blocks_exit_then_succeeds_after_rate_configured() {
        Appointment appt = readyForExit("R-NORATE", day, "12:00"); // 等待 120 分，计费 30 分

        // day 日没有任何费率：出场必须被拒绝
        loginAs("warehouse");
        assertThatThrownBy(() -> warehouse.exit(appt.getId(), userId("warehouse")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("费率未配置");

        // 出场事务整体回滚：车仍“卸货完成”、没有核费单
        Appointment reloaded = appointmentRepository.findById(appt.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(settlementRepository.findByAppointmentId(appt.getId())).isEmpty();

        // 补齐当日费率后重新出场，按 120 元/时结算（30 分 = 60.00），绝不是默认 60 元口径
        loginAs("dispatcher");
        rateService.upsertRate(carrierIdOf("carrier1"), day, new BigDecimal("120.00"),
                userId("dispatcher"));
        loginAs("warehouse");
        warehouse.exit(appt.getId(), userId("warehouse"));

        FeeSettlement fs = settlementRepository.findByAppointmentId(appt.getId()).orElseThrow();
        assertThat(fs.getChargeableMinutes()).isEqualTo(30);
        assertThat(fs.getOriginalAmount()).isEqualByComparingTo("60.00");
        assertThat(fs.getRateSnapshot()).isEqualByComparingTo("120.00");
    }

    @Test
    void thirty_seconds_over_free_time_charges_one_minute() {
        Appointment appt = readyForExit("R-30S", day.plusDays(1), "11:30:30"); // 等待 90 分 30 秒
        loginAs("dispatcher");
        rateService.upsertRate(carrierIdOf("carrier1"), day.plusDays(1), new BigDecimal("120.00"),
                userId("dispatcher"));
        loginAs("warehouse");
        warehouse.exit(appt.getId(), userId("warehouse"));

        FeeSettlement fs = settlementRepository.findByAppointmentId(appt.getId()).orElseThrow();
        assertThat(fs.getWaitMinutes()).isEqualTo(90);
        assertThat(fs.getChargeableMinutes()).isEqualTo(1);
        assertThat(fs.getOriginalAmount()).isEqualByComparingTo("2.00"); // 120 元/时 × 1 分
        assertThat(feeService.segments(fs.getId())).hasSize(1);
        assertThat(feeService.segments(fs.getId()).get(0).getMinutes()).isEqualTo(1);
    }

    @Test
    void within_free_time_needs_no_rate_and_is_free() {
        Appointment appt = readyForExit("R-FREE", day.plusDays(2), "11:29:50"); // 等待 89 分 50 秒
        loginAs("warehouse");
        warehouse.exit(appt.getId(), userId("warehouse")); // 该日无费率也必须能免费出场

        FeeSettlement fs = settlementRepository.findByAppointmentId(appt.getId()).orElseThrow();
        assertThat(fs.getChargeableMinutes()).isZero();
        assertThat(fs.getOriginalAmount()).isEqualByComparingTo("0.00");
        assertThat(feeService.segments(fs.getId())).isEmpty();
    }

    @Test
    void generated_at_uses_business_clock_and_lands_on_business_day() {
        Appointment appt = readyForExit("R-CLOCK", day.plusDays(3), "12:00");
        loginAs("dispatcher");
        rateService.upsertRate(carrierIdOf("carrier1"), day.plusDays(3), new BigDecimal("120.00"),
                userId("dispatcher"));
        loginAs("warehouse");
        warehouse.exit(appt.getId(), userId("warehouse"));

        FeeSettlement fs = settlementRepository.findByAppointmentId(appt.getId()).orElseThrow();
        // 冻结时钟在 day+3 的 12:00：generated_at 必须等于该业务时刻
        assertThat(fs.getGeneratedAt()).isEqualTo(
                ZonedDateTime.of(day.plusDays(3), LocalTime.of(12, 0), ZONE).toInstant());
        // 按该业务自然日能查到这张单（而非按 JVM 真实今天）
        assertThat(feeService.settlementsOfDay(day.plusDays(3))).extracting(FeeSettlement::getId)
                .contains(fs.getId());
        assertThat(feeService.settlementsOfDay(LocalDate.now(ZONE))).extracting(FeeSettlement::getId)
                .doesNotContain(fs.getId());
    }
}
