package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.domain.AppointmentStatus;
import com.example.dockyard.domain.DockType;
import com.example.dockyard.domain.FeeSettlement;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.CarrierDailyRateRepository;
import com.example.dockyard.repo.FeeSettlementRepository;
import com.example.dockyard.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 费率守卫：
 *  - 计费当日缺费率时出场被硬拒绝、出场事务回滚（车仍“卸货完成”、无核费单）；
 *  - 补配费率后重新出场成功，金额按等待分钟×当日费率正确核算；
 *  - 免费时长（90 分）内即使当日无费率也能 0 元出场；
 *  - 同承运商同日并发 upsert 费率只产生一行。
 */
class FeeRateGuardTest extends AbstractIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired GateService gateService;
    @Autowired QueueAllocationService queue;
    @Autowired WarehouseOperationService warehouse;
    @Autowired RateService rateService;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired FeeSettlementRepository settlementRepository;
    @Autowired CarrierDailyRateRepository rateRepository;
    @Autowired YardClock yardClock;

    private static final ZoneId ZONE = YardClock.ZONE;
    // 选一个种子费率（仅今/明日）覆盖不到的未来日期，天然“缺费率”
    private final LocalDate day = LocalDate.now(ZONE).plusDays(30);

    @BeforeEach
    void freezeClock() {
        freeze(day, "10:00");
    }

    @AfterEach
    void resetClock() {
        yardClock.setClock(Clock.system(ZONE));
    }

    private void freeze(LocalDate d, String hm) {
        yardClock.setClock(Clock.fixed(
                ZonedDateTime.of(d, LocalTime.parse(hm), ZONE).toInstant(), ZONE));
    }

    private Appointment drivenToCompleted(String orderNo, String plate, String arriveHm, String dockHm) {
        freeze(day, arriveHm);
        loginAs("carrier1");
        var req = new AppointmentService.BookingRequest(orderNo, plate, "司机", null,
                "常温食品", DockType.STANDARD, day, arriveHm);
        Appointment appt = appointmentService.book(req, carrierIdOf("carrier1"), userId("carrier1"));
        loginAs("guard");
        gateService.gateIn(appt.getCode(), userId("guard"));
        loginAs("dispatcher");
        Appointment called = queue.callToDock(appt.getId(), null, userId("dispatcher"));
        freeze(day, dockHm);
        loginAs("warehouse");
        warehouse.dock(called.getId(), called.getAssignedDockId(), userId("warehouse"));
        warehouse.startUnload(called.getId(), userId("warehouse"));
        warehouse.complete(called.getId(), userId("warehouse"));
        return appointmentRepository.findById(appt.getId()).orElseThrow();
    }

    @Test
    void missing_rate_blocks_exit_then_succeeds_after_rate_configured() {
        // 10:00 进场，12:00 靠台：等待 120 分，计费 30 分；该未来日无费率
        Appointment appt = drivenToCompleted("R-MISS", "费C0001", "10:00", "12:00");

        loginAs("warehouse");
        assertThatThrownBy(() -> warehouse.exit(appt.getId(), userId("warehouse")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("未配置费率");

        // 出场整体回滚：车仍“卸货完成”，且没有核费单
        Appointment stayed = appointmentRepository.findById(appt.getId()).orElseThrow();
        assertThat(stayed.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(stayed.getExitedAt()).isNull();
        assertThat(settlementRepository.findByAppointmentId(appt.getId())).isEmpty();

        // 调度补配当日费率 60 元/时后重新出场成功
        loginAs("dispatcher");
        rateService.upsertRate(carrierIdOf("carrier1"), day, new BigDecimal("60.00"), userId("dispatcher"));
        loginAs("warehouse");
        assertThatCode(() -> warehouse.exit(appt.getId(), userId("warehouse"))).doesNotThrowAnyException();

        Appointment exited = appointmentRepository.findById(appt.getId()).orElseThrow();
        assertThat(exited.getStatus()).isEqualTo(AppointmentStatus.EXITED);
        FeeSettlement fs = settlementRepository.findByAppointmentId(appt.getId()).orElseThrow();
        assertThat(fs.getWaitMinutes()).isEqualTo(120);
        assertThat(fs.getChargeableMinutes()).isEqualTo(30);
        assertThat(fs.getOriginalAmount()).isEqualByComparingTo("30.00"); // 30 分 × 60/60
    }

    @Test
    void free_within_90_minutes_exits_with_zero_fee_even_without_rate() {
        // 10:00 进场，11:00 靠台：等待 60 < 90，0 计费，不应要求费率
        Appointment appt = drivenToCompleted("R-FREE", "费C0002", "10:00", "11:00");
        assertThat(rateRepository.findByCarrierIdAndRateDate(carrierIdOf("carrier1"), day)).isEmpty();

        loginAs("warehouse");
        assertThatCode(() -> warehouse.exit(appt.getId(), userId("warehouse"))).doesNotThrowAnyException();

        FeeSettlement fs = settlementRepository.findByAppointmentId(appt.getId()).orElseThrow();
        assertThat(fs.getChargeableMinutes()).isZero();
        assertThat(fs.getOriginalAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void concurrent_upsert_same_day_creates_single_row() throws Exception {
        Long carrierId = carrierIdOf("carrier1");
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (BigDecimal rate : new BigDecimal[]{new BigDecimal("60.00"), new BigDecimal("80.00")}) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        loginAs("dispatcher");
                        rateService.upsertRate(carrierId, day, rate, userId("dispatcher"));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        long rows = rateRepository.findByCarrierIdOrderByRateDateDesc(carrierId).stream()
                .filter(r -> r.getRateDate().equals(day))
                .count();
        assertThat(rows).isEqualTo(1);
    }
}
