package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.domain.AppointmentStatus;
import com.example.dockyard.domain.DockType;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.service.AppointmentService;
import com.example.dockyard.service.BusinessRuleException;
import com.example.dockyard.service.GateService;
import com.example.dockyard.service.YardClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 场景：门卫放行。
 *  - 早于时段 -> 早到等候；
 *  - 晚于时段终点 45 分钟 -> 迟到标记（边界 45 分钟不标，46 分钟标）；
 *  - 同一预约码重复进场被拒绝。
 */
class LateArrivalTest extends AbstractIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired GateService gateService;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired YardClock yardClock;

    private static final ZoneId ZONE = YardClock.ZONE;
    private final LocalDate day = LocalDate.now(ZONE).plusDays(11);

    @AfterEach
    void reset() {
        yardClock.setClock(Clock.system(ZONE));
    }

    private void freezeAt(String hm) {
        yardClock.setClock(Clock.fixed(
                java.time.ZonedDateTime.of(day, java.time.LocalTime.parse(hm), ZONE).toInstant(), ZONE));
    }

    private Appointment book(String plate) {
        freezeAt("09:00");
        loginAs("carrier1");
        var req = new AppointmentService.BookingRequest("L-" + plate, plate, "司机", null,
                "常温食品", DockType.STANDARD, day, "10:00");
        return appointmentService.book(req, carrierIdOf("carrier1"), userId("carrier1"));
    }

    @Test
    void early_arrival_is_marked_and_sent_to_waiting() {
        Appointment appt = book("测B001");
        freezeAt("09:30"); // 早于 10:00
        loginAs("guard");
        var result = gateService.gateIn(appt.getCode(), userId("guard"));

        assertThat(result.early()).isTrue();
        assertThat(result.late()).isFalse();
        var saved = appointmentRepository.findById(appt.getId()).orElseThrow();
        assertThat(saved.isEarlyArrival()).isTrue();
        assertThat(saved.getStatus()).isEqualTo(AppointmentStatus.GATED_IN);
    }

    @Test
    void exactly_45_minutes_late_is_not_flagged_but_46_is() {
        Appointment within = book("测B002");
        freezeAt("11:15"); // 时段终点 10:30 + 45 分钟 = 11:15，边界不算迟到
        loginAs("guard");
        assertThat(gateService.gateIn(within.getCode(), userId("guard")).late()).isFalse();

        Appointment over = book("测B003");
        freezeAt("11:16"); // 超过 45 分钟 1 分钟
        loginAs("guard");
        var result = gateService.gateIn(over.getCode(), userId("guard"));
        assertThat(result.late()).isTrue();
        assertThat(result.lateMinutes()).isEqualTo(46);
        assertThat(appointmentRepository.findById(over.getId()).orElseThrow().isLateFlag()).isTrue();
    }

    @Test
    void same_code_cannot_gate_in_twice() {
        Appointment appt = book("测B004");
        freezeAt("10:05");
        loginAs("guard");
        gateService.gateIn(appt.getCode(), userId("guard"));

        assertThatThrownBy(() -> gateService.gateIn(appt.getCode(), userId("guard")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("禁止重复进场");
    }
}
