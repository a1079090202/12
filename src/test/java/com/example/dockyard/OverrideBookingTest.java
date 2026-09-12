package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.domain.AppointmentStatus;
import com.example.dockyard.domain.DockType;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.OperationEventRepository;
import com.example.dockyard.service.AppointmentService;
import com.example.dockyard.service.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 场景：30 分钟容量拦截 + 调度员写明原因插单。 */
class OverrideBookingTest extends AbstractIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired OperationEventRepository eventRepository;

    private final LocalDate day = LocalDate.now(com.example.dockyard.service.YardClock.ZONE).plusDays(10);

    private AppointmentService.BookingRequest req(String plate) {
        return new AppointmentService.BookingRequest("O-" + plate, plate, "司机", null,
                "常温食品", DockType.STANDARD, day, "14:00");
    }

    @Test
    void capacity_blocks_seventh_then_dispatcher_override_with_reason_succeeds() {
        loginAs("carrier1");
        Long carrier = carrierIdOf("carrier1");
        for (int i = 1; i <= 6; i++) {
            appointmentService.book(req("测A" + String.format("%03d", i)), carrier, userId("carrier1"));
        }

        // 第 7 辆被容量拦截
        assertThatThrownBy(() ->
                appointmentService.book(req("测A007"), carrier, userId("carrier1")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("容量 6 已满");

        // 没写原因不允许插单
        loginAs("dispatcher");
        assertThatThrownBy(() ->
                appointmentService.override(req("测A007"), carrier, userId("dispatcher"), "  "))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("必须写明原因");

        // 写明原因后插单成功
        Appointment overridden = appointmentService.override(
                req("测A007"), carrier, userId("dispatcher"), "客户产线待料，加急插单");
        assertThat(overridden.getStatus()).isEqualTo(AppointmentStatus.OVERRIDDEN);
        assertThat(overridden.getOverrideReason()).contains("加急插单");
        assertThat(overridden.getOverriddenBy()).isEqualTo(userId("dispatcher"));

        // 插单与原因进了只追加的操作留痕
        var events = eventRepository.findByAppointmentIdOrderByOccurredAtAsc(overridden.getId());
        assertThat(events).extracting(e -> e.getEventType().name())
                .contains("OVERRIDE");
        assertThat(events.stream().anyMatch(e -> e.getDetail() != null
                && e.getDetail().contains("加急插单"))).isTrue();

        var saved = appointmentRepository.findById(overridden.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(AppointmentStatus.OVERRIDDEN);
        assertThat(saved.getOverrideReason()).contains("加急插单");
    }
}
