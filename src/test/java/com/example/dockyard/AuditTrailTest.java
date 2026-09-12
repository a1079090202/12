package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.domain.DockType;
import com.example.dockyard.domain.EventType;
import com.example.dockyard.domain.OperationEvent;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.OperationEventRepository;
import com.example.dockyard.service.AppointmentService;
import com.example.dockyard.service.DisputeService;
import com.example.dockyard.service.YardClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计留痕可信度：operation_event 里的事件类型必须如实反映发生的动作。
 *  - 取消预约记 CANCELLED（不能再伪装成「提交预约」BOOKED）；
 *  - 承运商提异议必须有 DISPUTE_RAISED 留痕（此前该动作完全无痕）；
 *  - 调度驳回/调整分别记 DISPUTE_REJECTED / DISPUTE_ADJUSTED，
 *    不得伪装成 FEE_SETTLED（出场核费）——时间线上「出场核费」只应出现一次。
 */
class AuditTrailTest extends AbstractIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired DisputeService disputeService;
    @Autowired OperationEventRepository eventRepository;
    @Autowired AppointmentRepository appointmentRepository;

    private final LocalDate day = LocalDate.now(YardClock.ZONE).plusDays(25);

    private List<OperationEvent> eventsOf(Long appointmentId) {
        return eventRepository.findByAppointmentIdOrderByOccurredAtAsc(appointmentId);
    }

    private long countOf(List<OperationEvent> events, EventType type) {
        return events.stream().filter(e -> e.getEventType() == type).count();
    }

    @Test
    void cancel_records_cancelled_event_not_booked() {
        loginAs("carrier1");
        Appointment appt = appointmentService.book(
                new AppointmentService.BookingRequest("AUDIT-C", "审C0001", "司机", null,
                        "常温食品", DockType.STANDARD, day, "13:00"),
                carrierIdOf("carrier1"), userId("carrier1"));
        appointmentService.cancel(appt.getId(), userId("carrier1"));

        List<OperationEvent> events = eventsOf(appt.getId());
        // 提交预约只在下单时出现一次；取消这一步必须是独立的 CANCELLED 事件
        assertThat(countOf(events, EventType.BOOKED)).isEqualTo(1);
        assertThat(countOf(events, EventType.CANCELLED)).isEqualTo(1);

        OperationEvent cancel = events.stream()
                .filter(e -> e.getEventType() == EventType.CANCELLED).findFirst().orElseThrow();
        assertThat(cancel.getDetail()).contains("取消");
        assertThat(cancel.getActorId()).isEqualTo(userId("carrier1"));
    }

    @Test
    void dispute_lifecycle_records_distinct_trustworthy_events() {
        // 样例单 SO-DONE-01（carrier1，沪A00023）已出场核费 60.00 元，出场核费事件已有 1 条
        Appointment exited = appointmentRepository.findByOrderNo("SO-DONE-01").orElseThrow();
        assertThat(countOf(eventsOf(exited.getId()), EventType.FEE_SETTLED)).isEqualTo(1);

        loginAs("carrier1");
        disputeService.raise(exited.getId(), "审计：费用有异议",
                carrierIdOf("carrier1"), userId("carrier1"));

        loginAs("dispatcher");
        var open = disputeService.openDisputes().stream()
                .filter(d -> d.getAppointmentId().equals(exited.getId())).findFirst().orElseThrow();
        disputeService.reject(open.getId(), "维持原价", userId("dispatcher"));

        // 再次提起并成立调整
        loginAs("carrier1");
        disputeService.raise(exited.getId(), "审计：补充证据",
                carrierIdOf("carrier1"), userId("carrier1"));
        loginAs("dispatcher");
        var open2 = disputeService.openDisputes().stream()
                .filter(d -> d.getAppointmentId().equals(exited.getId())).findFirst().orElseThrow();
        disputeService.adjust(open2.getId(), new BigDecimal("30.00"), "部分责任在园区",
                userId("dispatcher"));

        List<OperationEvent> events = eventsOf(exited.getId());
        assertThat(countOf(events, EventType.DISPUTE_RAISED)).isEqualTo(2);
        assertThat(countOf(events, EventType.DISPUTE_REJECTED)).isEqualTo(1);
        assertThat(countOf(events, EventType.DISPUTE_ADJUSTED)).isEqualTo(1);
        // 异议处理不得新增「出场核费」事件：仍只有播种时的那 1 条
        assertThat(countOf(events, EventType.FEE_SETTLED)).isEqualTo(1);

        OperationEvent raise = events.stream()
                .filter(e -> e.getEventType() == EventType.DISPUTE_RAISED).findFirst().orElseThrow();
        assertThat(raise.getActorId()).isEqualTo(userId("carrier1"));
        assertThat(raise.getDetail()).contains("费用有异议");

        OperationEvent reject = events.stream()
                .filter(e -> e.getEventType() == EventType.DISPUTE_REJECTED).findFirst().orElseThrow();
        assertThat(reject.getActorId()).isEqualTo(userId("dispatcher"));
        assertThat(reject.getDetail()).contains("驳回");

        OperationEvent adjust = events.stream()
                .filter(e -> e.getEventType() == EventType.DISPUTE_ADJUSTED).findFirst().orElseThrow();
        assertThat(adjust.getDetail()).contains("60.00").contains("30.00");
    }
}
