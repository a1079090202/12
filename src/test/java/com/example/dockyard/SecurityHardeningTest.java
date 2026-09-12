package com.example.dockyard;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.service.AppointmentService;
import com.example.dockyard.service.GateService;
import com.example.dockyard.service.QueueAllocationService;
import com.example.dockyard.service.WarehouseOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全加固回归：
 *  - 看板按承运商水平隔离（等待车辆/核费金额），门卫看不到费用；
 *  - 改 URL 越权访问别司预约详情 -> 403；
 *  - CSV 公式字符被转义（Excel CSV Injection）；
 *  - 非法表单输入 -> 400，不透传到 500；
 *  - 自助改密流程可用。
 */
@AutoConfigureMockMvc
class SecurityHardeningTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired AppointmentService appointmentService;
    @Autowired GateService gateService;
    @Autowired QueueAllocationService queueService;
    @Autowired WarehouseOperationService warehouseService;

    private MockHttpSession login(String username) throws Exception {
        MvcResult r = mvc.perform(formLogin().user(username).password("dock1234"))
                .andExpect(status().is3xxRedirection()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void dashboard_is_scoped_per_carrier_and_guards_see_no_fees() throws Exception {
        // 样例中的等候车（沪A00021）与已核费车（沪A00023，60.00 元）都属于 carrier1
        MockHttpSession carrier1 = login("carrier1");
        String dash1 = mvc.perform(get("/").session(carrier1))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(dash1).contains("沪A00021", "60.00");

        // carrier2 首页不得出现 carrier1 的车牌、金额
        MockHttpSession carrier2 = login("carrier2");
        String dash2 = mvc.perform(get("/").session(carrier2))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(dash2).doesNotContain("沪A00021", "沪A00023", "60.00");

        // 调度员看得到全部
        MockHttpSession dispatcher = login("dispatcher");
        String dashD = mvc.perform(get("/").session(dispatcher))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(dashD).contains("沪A00021", "60.00");

        // 门卫不可下载费用 CSV
        MockHttpSession guard = login("guard");
        mvc.perform(get("/fees/download").session(guard))
                .andExpect(status().isForbidden());
    }

    @Test
    void carrier_cannot_open_other_carriers_appointment() throws Exception {
        Appointment c1Appt = appointmentRepository.findByOrderNo("SO-WAIT-01").orElseThrow();
        MockHttpSession carrier2 = login("carrier2");
        mvc.perform(get("/appointments/" + c1Appt.getId()).session(carrier2))
                .andExpect(status().isForbidden());
    }

    @Test
    void csv_formula_injection_payload_is_neutralized() throws Exception {
        // 车牌以 = 开头（承运商可控字段），走完进场→出场，核费后应出现在 CSV 且被加单引号转义。
        // 用今天的槽位并冻结时钟：核费单 generated_at 走数据库 now()（真实今天），下载按时钟“今天”查询才能命中。
        loginAs("carrier1");
        LocalDate today = yardClock.today();
        Appointment appt = appointmentService.book(
                new AppointmentService.BookingRequest("SEC-1", "=2+3", "司机", null,
                        "常温食品", com.example.dockyard.domain.DockType.STANDARD,
                        today, "10:00"),
                carrierIdOf("carrier1"), userId("carrier1"));
        yardClock.setClock(java.time.Clock.fixed(
                ZonedDateTime.of(today, LocalTime.parse("10:00"),
                        com.example.dockyard.service.YardClock.ZONE).toInstant(),
                com.example.dockyard.service.YardClock.ZONE));

        loginAs("guard");
        gateService.gateIn(appt.getCode(), userId("guard"));
        loginAs("dispatcher");
        Appointment called = queueService.callToDock(appt.getId(), null, userId("dispatcher"));
        loginAs("warehouse");
        warehouseService.dock(called.getId(), called.getAssignedDockId(), userId("warehouse"));
        warehouseService.startUnload(called.getId(), userId("warehouse"));
        warehouseService.complete(called.getId(), userId("warehouse"));
        warehouseService.exit(called.getId(), userId("warehouse"));

        // 清掉服务层调用留在线程上的登录上下文，避免干扰后续 MockMvc 会话
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        MockHttpSession dispatcher = login("dispatcher");
        String csv = mvc.perform(get("/fees/download").session(dispatcher))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 被引号包裹且以单引号前缀开头，Excel 不会当公式执行
        assertThat(csv).contains("\"'=2+3\"");
        // 未加保护的裸单元格不得出现
        assertThat(csv).doesNotContain(",=2+3,");
    }

    @Test
    void invalid_booking_input_returns_400_not_500() throws Exception {
        MockHttpSession carrier = login("carrier1");
        // 非法槽位时间
        mvc.perform(post("/appointments").session(carrier).with(csrf())
                        .param("orderNo", "X1")
                        .param("plateNo", "沪A1234")
                        .param("driverName", "司机")
                        .param("cargoType", "常温食品")
                        .param("dockType", "STANDARD")
                        .param("slotDate", LocalDate.now().plusDays(1).toString())
                        .param("slotTime", "9:99"))
                .andExpect(status().isBadRequest());

        // 缺必填 + 超长订单号
        mvc.perform(post("/appointments").session(carrier).with(csrf())
                        .param("orderNo", "X".repeat(100))
                        .param("plateNo", "沪A1234")
                        .param("driverName", "司机")
                        .param("cargoType", "常温食品")
                        .param("dockType", "STANDARD")
                        .param("slotDate", LocalDate.now().plusDays(1).toString())
                        .param("slotTime", "10:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void self_service_password_change_works() throws Exception {
        MockHttpSession carrier = login("carrier1");
        mvc.perform(get("/account/password").session(carrier))
                .andExpect(status().isOk());

        // 旧密码错误：留在表单页（200）
        mvc.perform(post("/account/password").session(carrier).with(csrf())
                        .param("oldPassword", "wrong-password")
                        .param("newPassword", "new-pass-123")
                        .param("confirmPassword", "new-pass-123"))
                .andExpect(status().isOk());

        // 旧密码正确：跳转成功
        mvc.perform(post("/account/password").session(carrier).with(csrf())
                        .param("oldPassword", "dock1234")
                        .param("newPassword", "new-pass-123")
                        .param("confirmPassword", "new-pass-123"))
                .andExpect(status().is3xxRedirection());

        // 新密码可登录（成功重定向到 /），旧密码被拒（重定向到 /login?error）
        mvc.perform(formLogin().user("carrier1").password("new-pass-123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        mvc.perform(formLogin().user("carrier1").password("dock1234"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }
}
