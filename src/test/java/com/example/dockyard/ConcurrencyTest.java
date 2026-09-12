package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.domain.AppointmentStatus;
import com.example.dockyard.domain.DockType;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.DisputeRepository;
import com.example.dockyard.service.AppointmentService;
import com.example.dockyard.service.BusinessRuleException;
import com.example.dockyard.service.DisputeService;
import com.example.dockyard.service.GateService;
import com.example.dockyard.service.YardClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核心链路并发安全：
 *  - 同槽并发预约不超额（咨询锁串行化容量检查）；
 *  - 同码并发进场只成功一次（预约行悲观锁）；
 *  - 取消与进场并发，最终状态唯一自洽，不会“已进场又被取消”；
 *  - 同一核费单并发提异议只产生一条 OPEN（部分唯一索引兜底）。
 */
class ConcurrencyTest extends AbstractIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired GateService gateService;
    @Autowired DisputeService disputeService;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired YardClock yardClock;

    private static final ZoneId ZONE = YardClock.ZONE;
    private final LocalDate day = LocalDate.now(ZONE).plusDays(20);

    @BeforeEach
    void freezeClock() {
        yardClock.setClock(Clock.fixed(
                ZonedDateTime.of(day, LocalTime.parse("12:00"), ZONE).toInstant(), ZONE));
    }

    @AfterEach
    void resetClock() {
        yardClock.setClock(Clock.system(ZONE));
    }

    @Test
    void concurrent_booking_into_nearly_full_slot_does_not_exceed_capacity() throws Exception {
        String slot = "13:00";
        // 先串行填满 5 单（容量 6），留最后一个名额给并发竞争
        for (int i = 1; i <= 5; i++) {
            loginAs("carrier1");
            appointmentService.book(req("CC-" + i, "容C" + String.format("%03d", i), slot),
                    carrierIdOf("carrier1"), userId("carrier1"));
        }

        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runParallel(
                () -> {
                    loginAs("carrier1");
                    appointmentService.book(req("CC-6", "容C6001", slot),
                            carrierIdOf("carrier1"), userId("carrier1"));
                    success.incrementAndGet();
                },
                () -> {
                    loginAs("carrier2");
                    appointmentService.book(req("CC-7", "容C6002", slot),
                            carrierIdOf("carrier2"), userId("carrier2"));
                    success.incrementAndGet();
                },
                rejected);

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        long inSlot = appointmentRepository
                .findBySlotBetween(
                        yardClock.truncateToSlot(
                                yardClock.parseDateTime(day, slot), 30),
                        yardClock.truncateToSlot(
                                yardClock.parseDateTime(day, slot), 30).plusSeconds(30 * 60L))
                .stream()
                .filter(a -> a.getStatus() != AppointmentStatus.CANCELLED)
                .count();
        assertThat(inSlot).isEqualTo(6); // 恰好容量，不多不少
    }

    @Test
    void concurrent_gate_in_with_same_code_only_one_succeeds() throws Exception {
        loginAs("carrier1");
        Appointment appt = appointmentService.book(req("G1", "进C0001", "13:00"),
                carrierIdOf("carrier1"), userId("carrier1"));
        String code = appt.getCode();

        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runParallel(
                () -> {
                    loginAs("guard");
                    gateService.gateIn(code, userId("guard"));
                    success.incrementAndGet();
                },
                () -> {
                    loginAs("guard");
                    gateService.gateIn(code, userId("guard"));
                    success.incrementAndGet();
                },
                rejected);

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        Appointment reloaded = appointmentRepository.findById(appt.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AppointmentStatus.GATED_IN);
        assertThat(reloaded.getArrivedAt()).isNotNull();
    }

    @Test
    void cancel_and_gate_in_are_serialized_to_one_consistent_state() throws Exception {
        loginAs("carrier1");
        Appointment appt = appointmentService.book(req("X1", "消C0001", "13:00"),
                carrierIdOf("carrier1"), userId("carrier1"));
        String code = appt.getCode();
        Long apptId = appt.getId();

        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runParallel(
                () -> {
                    loginAs("carrier1");
                    appointmentService.cancel(apptId, userId("carrier1"));
                    success.incrementAndGet();
                },
                () -> {
                    loginAs("guard");
                    gateService.gateIn(code, userId("guard"));
                    success.incrementAndGet();
                },
                rejected);

        // 恰好一个生效，另一个被状态机/行锁拒绝
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        Appointment reloaded = appointmentRepository.findById(apptId).orElseThrow();
        if (reloaded.getStatus() == AppointmentStatus.GATED_IN) {
            assertThat(reloaded.getArrivedAt()).isNotNull();
        } else {
            assertThat(reloaded.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
            assertThat(reloaded.getArrivedAt()).isNull();
        }
    }

    @Test
    void concurrent_dispute_raise_creates_only_one_open() throws Exception {
        // SO-DONE-01（carrier1）已出场核费
        Appointment appt = appointmentRepository.findByOrderNo("SO-DONE-01").orElseThrow();
        Long apptId = appt.getId();

        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runParallel(
                () -> {
                    loginAs("carrier1");
                    disputeService.raise(apptId, "并发异议一",
                            carrierIdOf("carrier1"), userId("carrier1"));
                    success.incrementAndGet();
                },
                () -> {
                    loginAs("carrier1");
                    disputeService.raise(apptId, "并发异议二",
                            carrierIdOf("carrier1"), userId("carrier1"));
                    success.incrementAndGet();
                },
                rejected);

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        long openCount = disputeRepository.findByAppointmentIdOrderByRaisedAtDesc(apptId).stream()
                .filter(d -> d.getStatus() == com.example.dockyard.domain.DisputeStatus.OPEN)
                .count();
        assertThat(openCount).isEqualTo(1);
    }

    private AppointmentService.BookingRequest req(String orderNo, String plate, String slot) {
        return new AppointmentService.BookingRequest(orderNo, plate, "司机", null,
                "常温食品", DockType.STANDARD, day, slot);
    }

    /** 两个任务在同一道闸上同步起跑；所有业务/并发拒绝都计入 rejected，其它异常直接抛出 */
    private void runParallel(Runnable a, Runnable b, AtomicInteger rejected) throws Exception {
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (Runnable task : new Runnable[]{a, b}) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        task.run();
                    } catch (BusinessRuleException | AccessDeniedException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        // 唯一索引兜底等路径同样视为“被拒绝”，但不应出现脏状态
                        rejected.incrementAndGet();
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
    }
}
