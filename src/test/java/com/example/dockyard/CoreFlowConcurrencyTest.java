package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.domain.AppointmentStatus;
import com.example.dockyard.domain.Dispute;
import com.example.dockyard.domain.DisputeStatus;
import com.example.dockyard.domain.DockType;
import com.example.dockyard.domain.FeeSettlement;
import com.example.dockyard.domain.FeeStatus;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.DisputeRepository;
import com.example.dockyard.repo.FeeSettlementRepository;
import com.example.dockyard.service.AppointmentService;
import com.example.dockyard.service.BusinessRuleException;
import com.example.dockyard.service.DisputeService;
import com.example.dockyard.service.GateService;
import com.example.dockyard.service.YardClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预约 / 进场 / 取消 / 异议四个环节的并发安全测试。
 * 每个用例用栅栏让多个事务在同一瞬间发起，断言核心业务状态不被并发破坏：
 * 槽位不超卖、同码/同车不重复进场、进场与取消不互相穿透、异议不重复提起/结论不被覆盖。
 */
class CoreFlowConcurrencyTest extends AbstractIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired GateService gateService;
    @Autowired DisputeService disputeService;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired FeeSettlementRepository feeSettlementRepository;
    @Autowired YardClock yardClock;

    private static final ZoneId ZONE = YardClock.ZONE;
    private final LocalDate day = LocalDate.now(ZONE).plusDays(14);

    // ---- 预约：同一 30 分钟槽并发下单，容量（可用普通月台 4 个）绝不被突破 ----

    @Test
    void concurrent_bookings_same_slot_never_exceed_capacity() throws Exception {
        int threads = 12; // 容量 4 的三倍
        runParallel(threads, i -> {
            loginAs("carrier1");
            appointmentService.book(booking("并发车牌" + String.format("%03d", i)),
                    carrierIdOf("carrier1"), userId("carrier1"));
        });

        Instant slot = yardClock.truncateToSlot(yardClock.parseDateTime(day, "13:00"), 30);
        long activeInSlot = appointmentRepository
                .countBySlotStartAndStatusNot(slot, AppointmentStatus.CANCELLED);
        assertThat(activeInSlot).as("同一槽位有效预约数不得超过容量 4（D1–D4 普通月台）").isEqualTo(4);
    }

    // ---- 进场：同一预约码被两个门卫同时扫，只能放行一次 --------------------

    @Test
    void concurrent_gate_in_same_code_only_one_enters() throws Exception {
        Appointment appt = bookOne("DUP001");
        Outcome o = runParallel(2, i -> {
            loginAs("guard");
            gateService.gateIn(appt.getCode(), userId("guard"));
        });

        assertThat(o.success()).isEqualTo(1);
        assertThat(o.rejected()).isEqualTo(1);
        assertThat(appointmentRepository.findById(appt.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.GATED_IN);
    }

    // ---- 进场：同一车牌、两张预约并发进场，只能一辆在场（唯一索引兜底）----

    @Test
    void concurrent_gate_in_same_plate_different_code_only_one_enters() throws Exception {
        freezeAt("12:55");
        loginAs("carrier1");
        Appointment a = appointmentService.book(booking("SAMEPLATE"),
                carrierIdOf("carrier1"), userId("carrier1"));
        Appointment b = appointmentService.book(
                new AppointmentService.BookingRequest("C-SAME-2", "SAMEPLATE", "司机", null,
                        "常温食品", DockType.STANDARD, day, "13:30"),
                carrierIdOf("carrier1"), userId("carrier1"));

        Outcome o = runParallel(2, i -> {
            loginAs("guard");
            gateService.gateIn(i == 0 ? a.getCode() : b.getCode(), userId("guard"));
        });

        assertThat(o.success()).isEqualTo(1);
        assertThat(o.rejected()).isEqualTo(1);
        assertThat(appointmentRepository.findPlateInYard("SAMEPLATE")).hasSize(1);
    }

    // ---- 取消 vs 进场：同一单并发，二者互斥，状态只能落到其中一边 ----------

    @Test
    void concurrent_cancel_and_gate_in_are_mutually_exclusive() throws Exception {
        Appointment appt = bookOne("RACE001");

        Outcome o = runParallel(2, i -> {
            if (i == 0) {
                loginAs("carrier1");
                appointmentService.cancel(appt.getId(), userId("carrier1"));
            } else {
                loginAs("guard");
                gateService.gateIn(appt.getCode(), userId("guard"));
            }
        });

        assertThat(o.success()).as("取消与进场恰好一个成功").isEqualTo(1);
        assertThat(o.rejected()).isEqualTo(1);

        Appointment finalState = appointmentRepository.findById(appt.getId()).orElseThrow();
        assertThat(finalState.getStatus())
                .isIn(AppointmentStatus.CANCELLED, AppointmentStatus.GATED_IN);
        if (finalState.getStatus() == AppointmentStatus.CANCELLED) {
            // 取消赢了：车绝不能进场
            assertThat(finalState.getArrivedAt()).isNull();
        } else {
            // 进场赢了：进场时间必须已落库
            assertThat(finalState.getArrivedAt()).isNotNull();
        }
    }

    // ---- 异议：承运商双击重复提起，只能有一条 OPEN -------------------------

    @Test
    void concurrent_dispute_raise_only_one_open() throws Exception {
        Appointment exited = seededExited();

        Outcome o = runParallel(2, i -> {
            loginAs("carrier1");
            disputeService.raise(exited.getId(), "等待时间有异议（第 " + i + " 次提交）",
                    carrierIdOf("carrier1"), userId("carrier1"));
        });

        assertThat(o.success()).isEqualTo(1);
        assertThat(o.rejected()).isEqualTo(1);
        long open = disputeRepository.findByAppointmentIdOrderByRaisedAtDesc(exited.getId()).stream()
                .filter(d -> d.getStatus() == DisputeStatus.OPEN)
                .count();
        assertThat(open).as("同一核费单同时只允许一条 OPEN 异议").isEqualTo(1);
        assertThat(feeSettlementRepository.findByAppointmentId(exited.getId()).orElseThrow().getStatus())
                .isEqualTo(FeeStatus.DISPUTED);
    }

    // ---- 异议处理：两个调度并发处理同一异议，结论只能有一个、不被覆盖 ------

    @Test
    void concurrent_dispute_resolution_only_one_conclusion_wins() throws Exception {
        Appointment exited = seededExited();
        loginAs("carrier1");
        Dispute d = disputeService.raise(exited.getId(), "等待是月台故障导致",
                carrierIdOf("carrier1"), userId("carrier1"));

        Outcome o = runParallel(2, i -> {
            loginAs("dispatcher");
            if (i == 0) {
                disputeService.reject(d.getId(), "查监控属正常排队", userId("dispatcher"));
            } else {
                disputeService.adjust(d.getId(), new BigDecimal("30.00"), "部分责任在园区",
                        userId("dispatcher"));
            }
        });

        assertThat(o.success()).as("驳回与调整恰好一个成功").isEqualTo(1);
        assertThat(o.rejected()).isEqualTo(1);

        Dispute resolved = disputeRepository.findById(d.getId()).orElseThrow();
        FeeSettlement fs = feeSettlementRepository.findByAppointmentId(exited.getId()).orElseThrow();
        if (resolved.getStatus() == DisputeStatus.ADJUSTED) {
            assertThat(resolved.getAdjustedAmount()).isEqualByComparingTo("30.00");
            assertThat(fs.getStatus()).isEqualTo(FeeStatus.ADJUSTED);
            assertThat(fs.getFinalAmount()).isEqualByComparingTo("30.00");
        } else {
            assertThat(resolved.getStatus()).isEqualTo(DisputeStatus.REJECTED);
            assertThat(resolved.getAdjustedAmount()).isNull();
            assertThat(fs.getStatus()).isEqualTo(FeeStatus.UPHELD);
            assertThat(fs.getFinalAmount()).isEqualByComparingTo("60.00");
        }
        // 原始金额在任何结论下都不得被改动
        assertThat(fs.getOriginalAmount()).isEqualByComparingTo("60.00");
    }

    // ---- helpers ----------------------------------------------------------

    private Appointment seededExited() {
        return appointmentRepository.findByOrderNo("SO-DONE-01").orElseThrow();
    }

    private AppointmentService.BookingRequest booking(String plate) {
        return new AppointmentService.BookingRequest("C-" + plate, plate, "司机", null,
                "常温食品", DockType.STANDARD, day, "13:00");
    }

    private Appointment bookOne(String plate) {
        freezeAt("12:55");
        loginAs("carrier1");
        return appointmentService.book(booking(plate),
                carrierIdOf("carrier1"), userId("carrier1"));
    }

    private void freezeAt(String hm) {
        yardClock.setClock(Clock.fixed(
                java.time.ZonedDateTime.of(day, java.time.LocalTime.parse(hm), ZONE).toInstant(), ZONE));
    }

    private record Outcome(int success, int rejected) {}

    /** 用栅栏让 n 个工作线程在同一瞬间各自发起一个独立事务调用。 */
    private Outcome runParallel(int n, ThrowingBody body) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            for (int i = 0; i < n; i++) {
                int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        body.run(idx);
                        success.incrementAndGet();
                    } catch (BusinessRuleException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        // 数据库约束兜底抛出的其他异常也算“被拒绝”，但打印原因便于排查
                        rejected.incrementAndGet();
                        e.printStackTrace();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        return new Outcome(success.get(), rejected.get());
    }

    @FunctionalInterface
    private interface ThrowingBody {
        void run(int i) throws Exception;
    }
}
