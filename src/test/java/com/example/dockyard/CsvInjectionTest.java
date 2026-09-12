package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.repo.AppointmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSV 公式注入防护：以 = + - @ 等开头的用户可控字段（车牌）进入核费明细导出时，
 * 必须被前置单引号中和，Excel/WPS 打开不会当公式执行。
 */
@AutoConfigureMockMvc
class CsvInjectionTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AppointmentRepository appointmentRepository;

    private final LocalDate today = java.time.LocalDate.now(com.example.dockyard.service.YardClock.ZONE);

    @Test
    void formula_like_fields_are_neutralized_in_fee_csv() throws Exception {
        MockHttpSession carrier = login("carrier1");
        MockHttpSession guard = login("guard");
        MockHttpSession dispatcher = login("dispatcher");
        MockHttpSession warehouse = login("warehouse");

        String evilPlate = "=2+3";
        mvc.perform(post("/appointments").session(carrier).with(csrf())
                        .param("orderNo", "CSV-EVIL-01")
                        .param("plateNo", evilPlate)
                        .param("driverName", "+cmd测试")
                        .param("cargoType", "常温食品")
                        .param("dockType", "STANDARD")
                        .param("slotDate", today.toString())
                        .param("slotTime", "13:00"))
                .andExpect(status().is3xxRedirection());
        Appointment appt = appointmentRepository.findByOrderNo("CSV-EVIL-01").orElseThrow();
        // 车牌会被规范化为大写，但首字符 '=' 保留
        assertThat(appt.getPlateNo()).startsWith("=");

        // 走完 进场 → 叫号（自动派台）→ 卸货 → 出场核费
        mvc.perform(post("/gate/in").session(guard).with(csrf()).param("code", appt.getCode()))
                .andExpect(status().isOk());
        mvc.perform(post("/dispatch/call").session(dispatcher).with(csrf())
                        .param("apptId", String.valueOf(appt.getId())))
                .andExpect(status().is3xxRedirection());
        appt = appointmentRepository.findById(appt.getId()).orElseThrow();
        Long dockId = appt.getAssignedDockId();
        mvc.perform(post("/warehouse/" + appt.getId() + "/dock").session(warehouse).with(csrf())
                .param("dockId", String.valueOf(dockId))).andExpect(status().is3xxRedirection());
        mvc.perform(post("/warehouse/" + appt.getId() + "/start").session(warehouse).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/warehouse/" + appt.getId() + "/complete").session(warehouse).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/warehouse/" + appt.getId() + "/exit").session(warehouse).with(csrf()))
                .andExpect(status().is3xxRedirection());

        String csv = mvc.perform(get("/fees/download").session(dispatcher))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String line = java.util.Arrays.stream(csv.split("\n"))
                .filter(l -> l.contains("CSV-EVIL") || l.contains("'="))
                .findFirst().orElseThrow(() -> new AssertionError("CSV 中未找到恶意车牌行: " + csv));
        // 原始 "=2+3" 不得以未转义单元格出现；必须带中和前缀单引号
        assertThat(line).contains("'=");
        assertThat(line).doesNotContain(",=2+3,");
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult r = mvc.perform(formLogin().user(username).password("dock1234"))
                .andExpect(status().is3xxRedirection()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }
}
