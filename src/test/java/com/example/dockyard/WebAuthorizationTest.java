package com.example.dockyard;

import com.example.dockyard.repo.AppointmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务端鉴权：不靠页面隐藏按钮。
 *  - 跨角色直连 URL -> 403；
 *  - 承运商租户隔离：carrier2 访问/操作 carrier1 的预约与核费单 -> 一律 403（不是 200）；
 *  - 未登录访问首页 -> 跳转登录。
 */
@AutoConfigureMockMvc
class WebAuthorizationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AppointmentRepository appointmentRepository;

    @Test
    void cross_role_endpoints_are_forbidden_at_server_side() throws Exception {
        // 门卫访问调度派台
        mvc.perform(post("/dispatch/call").with(user("guard").roles("GUARD")).with(csrf())
                        .param("apptId", "1"))
                .andExpect(status().isForbidden());

        // 仓库访问门卫进场
        mvc.perform(post("/gate/in").with(user("warehouse").roles("WAREHOUSE")).with(csrf())
                        .param("code", "X"))
                .andExpect(status().isForbidden());

        // 承运商访问异议处理列表（仅调度）
        mvc.perform(get("/disputes").with(user("carrier1").roles("CARRIER")))
                .andExpect(status().isForbidden());

        // 未登录访问首页 -> 重定向到登录
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection());
    }

    /** 承运商只能碰本司数据：他司单的查看 / 取消 / 提异议都必须是 403 */
    @Test
    void carrier_cannot_read_or_touch_other_carrier_data() throws Exception {
        MockHttpSession carrier2 = login("carrier2");
        // SO-DONE-01 属于 carrier1（顺达）且已出场核费
        long c1ApptId = appointmentRepository.findByOrderNo("SO-DONE-01").orElseThrow().getId();

        // 查看他司预约详情 -> 403（不能返回 200 的错误页，否则可枚举单号）
        mvc.perform(get("/appointments/" + c1ApptId).session(carrier2))
                .andExpect(status().isForbidden());

        // 取消他司预约 -> 403
        mvc.perform(post("/appointments/" + c1ApptId + "/cancel").session(carrier2).with(csrf()))
                .andExpect(status().isForbidden());

        // 对他司核费单提异议 -> 403
        mvc.perform(post("/disputes/raise").session(carrier2).with(csrf())
                        .param("appointmentId", String.valueOf(c1ApptId))
                        .param("reason", "试图对他司单提异议"))
                .andExpect(status().isForbidden());
    }

    /** 费率维护仅调度员可用：他角色 403；调度员可打开页面、保存费率 */
    @Test
    void rate_management_is_dispatcher_only() throws Exception {
        // 承运商访问费率页 -> 403
        MockHttpSession carrier = login("carrier1");
        mvc.perform(get("/rates").session(carrier)).andExpect(status().isForbidden());

        // 调度员打开页面 -> 200
        MockHttpSession dispatcher = login("dispatcher");
        mvc.perform(get("/rates").session(dispatcher)).andExpect(status().isOk());

        // 调度员保存费率 -> 302 重定向回列表
        String future = LocalDate.now().plusDays(40).toString();
        mvc.perform(post("/rates").session(dispatcher).with(csrf())
                        .param("carrierId", String.valueOf(carrierIdOf("carrier1"))))
                .andExpect(status().isBadRequest()); // 缺日期/金额 -> 400，不是 500
        mvc.perform(post("/rates").session(dispatcher).with(csrf())
                        .param("carrierId", String.valueOf(carrierIdOf("carrier1")))
                        .param("rateDate", future)
                        .param("ratePerHour", "60.00"))
                .andExpect(status().is3xxRedirection());
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult r = mvc.perform(formLogin().user(username).password("dock1234"))
                .andExpect(status().is3xxRedirection()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }
}