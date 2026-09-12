package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.domain.AppointmentStatus;
import com.example.dockyard.domain.Dock;
import com.example.dockyard.domain.DockType;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.DockRepository;
import com.example.dockyard.service.AppointmentService;
import com.example.dockyard.service.BusinessRuleException;
import com.example.dockyard.service.GateService;
import com.example.dockyard.service.QueueAllocationService;
import com.example.dockyard.service.YardClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景：两个调度员同时把两辆不同的车派到同一个月台。
 * 行级锁串行化 + 部分唯一索引兜底，结果必须恰好一单成功、一单被拒，
 * 数据里该月台在派台时刻只有一个占用者（无双占）。
 */
class ConcurrentDockTest extends AbstractIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired GateService gateService;
    @Autowired QueueAllocationService queue;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired DockRepository dockRepository;
    @Autowired YardClock yardClock;

    private static final ZoneId ZONE = YardClock.ZONE;
    private final LocalDate day = LocalDate.now(ZONE).plusDays(12);

    @Test
    void two_dispatchers_assigning_same_dock_only_one_wins() throws Exception {
        // 选一个当前空闲的普通月台（样例数据里 D1 被一辆卸货中的车占用）
        Dock target = dockRepository.findByActiveTrueAndDockTypeOrderByCode(DockType.STANDARD).stream()
                .filter(d -> appointmentRepository.findOccupyingDock(d.getId()).isEmpty())
                .findFirst()
                .orElseThrow();

        Appointment a1 = prepareGated("测C001", "13:00");
        Appointment a2 = prepareGated("测C002", "13:00");

        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (Appointment a : new Appointment[]{a1, a2}) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        // 每个工作线程建立自己的登录上下文与事务边界（服务方法自带 @Transactional）
                        loginAs("dispatcher");
                        queue.callToDock(a.getId(), target.getId(), userId("dispatcher"));
                        success.incrementAndGet();
                    } catch (BusinessRuleException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        // 唯一索引兜底路径也视为“被拒绝”
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

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);

        // 数据库层最终只有一单占用该月台
        long occupying = appointmentRepository.findOccupyingDock(target.getId()).size();
        assertThat(occupying).isEqualTo(1);

        Appointment winner = appointmentRepository
                .findOccupyingDock(target.getId()).get(0);
        assertThat(winner.getStatus()).isEqualTo(AppointmentStatus.CALLED);

        Appointment loser = appointmentRepository.findById(
                winner.getId().equals(a1.getId()) ? a2.getId() : a1.getId()).orElseThrow();
        assertThat(loser.getStatus()).isEqualTo(AppointmentStatus.GATED_IN);
        assertThat(loser.getAssignedDockId()).isNull();
    }

    /** 预约并放行进场，得到一辆在等候区的车 */
    private Appointment prepareGated(String plate, String slot) {
        yardClock.setClock(Clock.fixed(
                java.time.ZonedDateTime.of(day, java.time.LocalTime.parse("12:00"), ZONE).toInstant(), ZONE));
        loginAs("carrier1");
        var req = new AppointmentService.BookingRequest("C-" + plate, plate, "司机", null,
                "常温食品", DockType.STANDARD, day, slot);
        Appointment appt = appointmentService.book(req, carrierIdOf("carrier1"), userId("carrier1"));
        loginAs("guard");
        gateService.gateIn(appt.getCode(), userId("guard"));
        return appointmentRepository.findById(appt.getId()).orElseThrow();
    }
}
