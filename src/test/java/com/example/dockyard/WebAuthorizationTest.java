package com.example.dockyard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务端鉴权：不靠页面隐藏按钮。
 *  - 门卫不能调调度派台、仓库不能放行、承运商不能进异议处理 -> 403；
 *  - 未登录访问首页 -> 跳转登录；
 *  - 登录后首页可见 6 个月台；
 *  - 下载的当天核费 CSV 与库内核费数字一致（样例 SO-DONE-01 = 60.00）。
 */
@AutoConfigureMockMvc
class WebAuthorizationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

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

    @Test
    void login_then_dashboard_shows_docks_and_csv_matches_settlement() throws Exception {
        // 用真实账号走表单登录，拿到会话（控制器依赖 LoginUser 主体）
        MvcResult login = mvc.perform(formLogin().user("dispatcher").password("dock1234"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        MvcResult dash = mvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andReturn();
        String html = dash.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("D1", "D6").contains("今日平均周转时间");

        MvcResult csv = mvc.perform(get("/fees/download").session(session))
                .andExpect(status().isOk())
                .andReturn();
        String body = csv.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("原始金额", "最终金额", "费率分段快照");
        String line = java.util.Arrays.stream(body.split("\n"))
                .filter(l -> l.contains("沪A00023")).findFirst().orElseThrow();
        // 等待150 / 免费90 / 计费60 / 原始60.00 / 最终60.00
        assertThat(line).contains("150", "90", "60", "60.00");
    }
}
