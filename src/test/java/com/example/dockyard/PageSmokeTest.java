package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.repo.AppointmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 各角色页面冒烟：真实登录后把主要页面都渲染一遍，捕获任何 Thymeleaf 模板错误。 */
@AutoConfigureMockMvc
class PageSmokeTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired com.example.dockyard.service.DockMaintenanceService dockMaintenanceService;

    private MockHttpSession login(String username) throws Exception {
        MvcResult r = mvc.perform(formLogin().user(username).password("dock1234"))
                .andExpect(status().is3xxRedirection()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void all_role_pages_render() throws Exception {
        // 调度员
        MockHttpSession dispatcher = login("dispatcher");
        expectOk(dispatcher, "/");
        expectOk(dispatcher, "/dispatch");
        expectOk(dispatcher, "/rates");
        expectOk(dispatcher, "/appointments/override");
        expectOk(dispatcher, "/disputes");
        // 月台保养：列表页 + 新建一条停用后的详情页（覆盖受影响清单模板）
        expectOk(dispatcher, "/maintenance");
        loginAs("dispatcher");
        Long d5 = jdbcTemplate.queryForObject("select id from dock where code = 'D5'", Long.class);
        Long maintId = dockMaintenanceService.register(
                d5,
                java.time.LocalDate.now(com.example.dockyard.service.YardClock.ZONE).plusDays(2),
                "08:00", "12:00", "冒烟：例行保养", userId("dispatcher")).getId();
        expectOk(dispatcher, "/maintenance/" + maintId);

        // 门卫
        MockHttpSession guard = login("guard");
        expectOk(guard, "/gate");

        // 仓库
        MockHttpSession warehouse = login("warehouse");
        expectOk(warehouse, "/warehouse");

        // 承运商
        MockHttpSession carrier = login("carrier1");
        expectOk(carrier, "/appointments/mine");
        expectOk(carrier, "/appointments/new");
        mvc.perform(get("/fees/download").session(carrier)).andExpect(status().isOk());

        // 详情页：已出场核费单（含核费区）与在场单（含时间线）都要能渲染
        Appointment exited = appointmentRepository.findByOrderNo("SO-DONE-01").orElseThrow();
        expectOk(dispatcher, "/appointments/" + exited.getId());
        Appointment waiting = appointmentRepository.findByOrderNo("SO-WAIT-01").orElseThrow();
        expectOk(dispatcher, "/appointments/" + waiting.getId());
        // 承运商看本司单
        expectOk(carrier, "/appointments/" + exited.getId());
    }

    private void expectOk(MockHttpSession session, String url) throws Exception {
        mvc.perform(get(url).session(session))
                .andExpect(status().isOk());
    }
}
