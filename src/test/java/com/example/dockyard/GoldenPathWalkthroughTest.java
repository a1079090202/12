package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.service.YardClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端走查（对应人工清单：预约两辆车—门卫放行—调度叫号—仓库卸货—出场核费—承运商提异议）。
 * A 车等待 105 分钟（计费 15 分 → 15.00 元），B 车等待 120 分钟（计费 30 分 → 30.00 元），
 * 叠加样例出场单 60.00，首页今日合计应为 105.00，且与下载 CSV 完全一致。
 */
@AutoConfigureMockMvc
class GoldenPathWalkthroughTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired YardClock yardClock;

    private static final ZoneId ZONE = YardClock.ZONE;
    private final LocalDate today = LocalDate.now(ZONE);

    @AfterEach
    void reset() {
        yardClock.setClock(Clock.system(ZONE));
    }

    private void freeze(String hm) {
        yardClock.setClock(Clock.fixed(
                ZonedDateTime.of(today, LocalTime.parse(hm), ZONE).toInstant(), ZONE));
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult r = mvc.perform(formLogin().user(username).password("dock1234"))
                .andExpect(status().is3xxRedirection()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void full_flow_book_gate_call_unload_exit_fee_dispute_reconciles() throws Exception {
        MockHttpSession carrier = login("carrier1");
        MockHttpSession guard = login("guard");
        MockHttpSession dispatcher = login("dispatcher");
        MockHttpSession warehouse = login("warehouse");

        freeze("10:00");

        // 1) 承运商预约两辆车
        book(carrier, "GP-A", "黄金甲");
        book(carrier, "GP-B", "黄金乙");
        Appointment a = appointmentRepository.findByOrderNo("GP-A").orElseThrow();
        Appointment b = appointmentRepository.findByOrderNo("GP-B").orElseThrow();

        // 2) 门卫放行两车进场
        mvc.perform(post("/gate/in").session(guard).with(csrf()).param("code", a.getCode()))
                .andExpect(status().isOk());
        mvc.perform(post("/gate/in").session(guard).with(csrf()).param("code", b.getCode()))
                .andExpect(status().isOk());

        // 首页此时应有 2 辆等待车
        String dashWaiting = mvc.perform(get("/").session(dispatcher))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(dashWaiting).contains("黄金甲", "黄金乙");

        // 3) 调度叫号（自动派台）
        mvc.perform(post("/dispatch/call").session(dispatcher).with(csrf())
                        .param("apptId", String.valueOf(a.getId())))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/dispatch/call").session(dispatcher).with(csrf())
                        .param("apptId", String.valueOf(b.getId())))
                .andExpect(status().is3xxRedirection());
        a = appointmentRepository.findById(a.getId()).orElseThrow();
        b = appointmentRepository.findById(b.getId()).orElseThrow();
        assertThat(a.getAssignedDockId()).isNotNull();
        assertThat(b.getAssignedDockId()).isNotEqualTo(a.getAssignedDockId());

        // 4)+5) A 车 11:45 靠台（等待105分）→ 卸货 → 完成 → 出场核费
        freeze("11:45");
        flowExit(warehouse, a);

        // B 车 12:00 靠台（等待120分）→ 出场核费
        freeze("12:00");
        flowExit(warehouse, b);

        // 6) 首页数字：今日合计 60（样例）+15+30 = 105.00
        String dash = mvc.perform(get("/").session(dispatcher))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(dash).contains("105.00");

        // 7) 下载 CSV 与页面一致
        String csv = mvc.perform(get("/fees/download").session(dispatcher))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String lineA = lineOf(csv, a.getPlateNo());
        String lineB = lineOf(csv, b.getPlateNo());
        assertThat(lineA).contains("105", "90", "15", "15.00", "15.00"); // 等待105/免费90/计费15
        assertThat(lineB).contains("120", "90", "30", "30.00", "30.00"); // 等待120/计费30

        // 8) 承运商对 A 单提异议
        mvc.perform(post("/disputes/raise").session(carrier).with(csrf())
                        .param("appointmentId", String.valueOf(a.getId()))
                        .param("reason", "等待系月台故障，申请减免"))
                .andExpect(status().is3xxRedirection());

        // 调度异议列表能看到该车牌与原金额，且详情页原始金额仍为 15.00
        String disputePage = mvc.perform(get("/disputes").session(dispatcher))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(disputePage).contains("等待系月台故障").contains("15.00");

        String detail = mvc.perform(get("/appointments/" + a.getId()).session(carrier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail).contains("DISPUTED");
        assertThat(detail).contains("15.00"); // 原始金额未被抹掉
    }

    private void book(MockHttpSession session, String orderNo, String plate) throws Exception {
        mvc.perform(post("/appointments").session(session).with(csrf())
                        .param("orderNo", orderNo)
                        .param("plateNo", plate)
                        .param("driverName", "司机" + plate)
                        .param("cargoType", "常温食品")
                        .param("dockType", "STANDARD")
                        .param("slotDate", today.toString())
                        .param("slotTime", "10:00"))
                .andExpect(status().is3xxRedirection());
    }

    private void flowExit(MockHttpSession session, Appointment appt) throws Exception {
        Long id = appt.getId();
        Long dock = appt.getAssignedDockId();
        mvc.perform(post("/warehouse/" + id + "/dock").session(session).with(csrf())
                .param("dockId", String.valueOf(dock))).andExpect(status().is3xxRedirection());
        mvc.perform(post("/warehouse/" + id + "/start").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/warehouse/" + id + "/complete").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/warehouse/" + id + "/exit").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    private String lineOf(String csv, String plate) {
        return java.util.Arrays.stream(csv.split("\n"))
                .filter(l -> l.contains(plate)).findFirst().orElseThrow();
    }
}
