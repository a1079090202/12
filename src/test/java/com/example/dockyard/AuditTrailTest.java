package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.domain.DockType;
import com.example.dockyard.domain.EventType;
import com.example.dockyard.domain.OperationEvent;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.OperationEventRepository;
import com.example.dockyard.service.AppointmentService;
import com.example.dockyard.service.DisputeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计留痕可信度：
 *  - 取消预约必须记 CANCELLED（不能再伪装成“提交预约” BOOKED）；
 *  - 承运商提异议必须有 DISPUTE_RAISED 留痕（此前该动作完全无痕）；
 *  - 调度驳回记 DISPUTE_REJECTED，不得伪装成 FEE_SETTLED（出场核费）。
 */
class AuditTrailTest extends AbstractIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired DisputeService disputeService;
    @Autowired OperationEventRepository eventRepository;
    @Autowired AppointmentRepository appointmentRepository;

    private final LocalDate day = LocalDate.now(com.example.dockyard.service.YardClock.ZONE).plusDays(25);

    @Test
    void cancel_records_cancelled_event_not_booked() {
        loginAs("carrier1");
        Appointment appt = appointmentService.book(
                new AppointmentService.BookingRequest("AUDIT-C", "审C0001", "司机", null,
                        "常温食品", DockType.STANDARD, day, "13:00"),
                carrierIdOf("carrier1"), userId("carrier1"));
        appointmentService.cancel(appt.getId(), userId("carrier1"));

        List<OperationEvent> events = eventRepository.findByAppointmentIdOrderByOccurredAtAsc(appt.getId());
        assertThat(events).extracting(OperationEvent::getEventType).contains(EventType.CANCELLED);
        // 取消这一步不得记成提交预约：BOOKED 只允许出现一次（提交时）
        long bookedCount = events.stream().filter(e -> e.getEventType() == EventType.BOOKED).count();
        assertThat(bookedCount).isEqualTo(1);
        OperationEvent cancel = events.stream()
                .filter(e -> e.getEventType() == EventType.CANCELLED).findFirst().orElseThrow();
        assertThat(cancel.getDetail()).contains("取消");
        assertThat(cancel.getActorId()).isEqualTo(userId("carrier1"));
    }

    @Test
    void dispute_lifecycle_records_distinct_trustworthy_events() {
        // 样例 SO-DONE-01（carrier1）已出场核费
        Appointment exited = appointmentSeeded("SO-DONE-01");

        loginAs("carrier1");
        disputeService.raise(exited.getId(), "审计：费用有异议",
                carrierIdOf("carrier1"), userId("carrier1"));

        loginAs("dispatcher");
        var open = disputeService.openDisputes().stream()
                .filter(d -> d.getAppointmentId().equals(exited.getId())).findFirst().orElseThrow();
        disputeService.reject(open.getId(), "维持原价", userId("dispatcher"));

        List<OperationEvent> events = eventRepository
                .findByAppointmentIdOrderByOccurredAtAsc(exited.getId());
        assertThat(events).extracting(OperationEvent::getEventType)
                .contains(EventType.DISPUTE_RAISED, EventType.DISPUTE_REJECTED);
        // 驳回不得伪装成出场核费：本单是种子数据，出场核费允许 1 条，但驳回那条必须是 REJECTED
        OperationEvent rejectEvent = events.stream()
                .filter(e -> e.getEventType() == EventType.DISPUTE_REJECTED).findFirst().orElseThrow();
        assertThat(rejectEvent.getDetail()).contains("驳回");
        assertThat(rejectEvent.getActorId()).isEqualTo(userId("dispatcher"));

        OperationEvent raiseEvent = events.stream()
                .filter(e -> e.getEventType() == EventType.DISPUTE_RAISED).findFirst().orElseThrow();
        assertThat(raiseEvent.getActorId()).isEqualTo(userId("carrier1"));
        assertThat(raiseEvent.getDetail()).contains("费用有异议");
    }

    private Appointment appointmentSeeded(String orderNo) {
        return appointmentRepository.findByOrderNo(orderNo).orElseThrow();
    }
}
