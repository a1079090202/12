package com.example.dockyard;

import com.example.dockyard.security.LoginLockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录失败锁定：同一账号连续失败 5 次锁 15 分钟；
 * 锁定期内即使密码正确也拒绝；一次成功登录会清零计数。
 */
@AutoConfigureMockMvc
class LoginLockoutTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired LoginLockService lockService;

    @AfterEach
    void resetLocks() {
        // 锁定状态是进程内单例，避免污染其他测试类
        lockService.clearAll();
    }

    @Test
    void five_failures_lock_account_and_correct_password_is_rejected() throws Exception {
        for (int i = 1; i <= 4; i++) {
            mvc.perform(formLogin().user("guard").password("wrong-pwd"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?error"));
        }
        // 第 5 次失败：触发锁定
        mvc.perform(formLogin().user("guard").password("wrong-pwd"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?locked"));

        // 锁定期内即使密码正确也拒绝
        mvc.perform(formLogin().user("guard").password("dock1234"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?locked"));
    }

    @Test
    void successful_login_resets_failure_counter() throws Exception {
        for (int i = 1; i <= 4; i++) {
            mvc.perform(formLogin().user("warehouse").password("bad"))
                    .andExpect(redirectedUrl("/login?error"));
        }
        // 成功登录清零
        mvc.perform(formLogin().user("warehouse").password("dock1234"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        // 重新计数：再来 4 次失败仍不应锁定
        for (int i = 1; i <= 4; i++) {
            mvc.perform(formLogin().user("warehouse").password("bad"))
                    .andExpect(redirectedUrl("/login?error"));
        }
    }
}
