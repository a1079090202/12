package com.example.dockyard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务端入参校验：缺参 / 超长 / 格式非法一律 400（友好错误页，不是 500/堆栈）。
 */
@AutoConfigureMockMvc
class InputValidationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private final LocalDate today = LocalDate.now(com.example.dockyard.service.YardClock.ZONE);

    @Test
    void booking_requires_valid_fields() throws Exception {
        MockHttpSession carrier = login("carrier1");

        // 缺必填（订单号为空）→ 400
        mvc.perform(post("/appointments").session(carrier).with(csrf())
                        .param("orderNo", "")
                        .param("plateNo", "沪A12348")
                        .param("driverName", "张三")
                        .param("cargoType", "常温食品")
                        .param("dockType", "STANDARD")
                        .param("slotDate", today.toString())
                        .param("slotTime", "10:00"))
                .andExpect(status().isBadRequest());

        // 超长订单号（varchar(64)，提交 65 字符）→ 400，不能穿透到数据库
        mvc.perform(post("/appointments").session(carrier).with(csrf())
                        .param("orderNo", "x".repeat(65))
                        .param("plateNo", "沪A12348")
                        .param("driverName", "张三")
                        .param("cargoType", "常温食品")
                        .param("dockType", "STANDARD")
                        .param("slotDate", today.toString())
                        .param("slotTime", "10:00"))
                .andExpect(status().isBadRequest());

        // 非法时段格式（非 HH:mm 整点）→ 400
        mvc.perform(post("/appointments").session(carrier).with(csrf())
                        .param("orderNo", "SO-V-01")
                        .param("plateNo", "沪A12348")
                        .param("driverName", "张三")
                        .param("cargoType", "常温食品")
                        .param("dockType", "STANDARD")
                        .param("slotDate", today.toString())
                        .param("slotTime", "9:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dispute_and_gate_params_validated() throws Exception {
        MockHttpSession carrier = login("carrier1");
        MockHttpSession guard = login("guard");

        // 异议原因空白 → 400（在校验层就拦下，不进入服务层）
        mvc.perform(post("/disputes/raise").session(carrier).with(csrf())
                        .param("appointmentId", "1")
                        .param("reason", "   "))
                .andExpect(status().isBadRequest());

        // 门卫预约码空白 → 400
        mvc.perform(post("/gate/in").session(guard).with(csrf())
                        .param("code", ""))
                .andExpect(status().isBadRequest());

        // 异议调整金额为负 → 400
        MockHttpSession dispatcher = login("dispatcher");
        mvc.perform(post("/disputes/999/adjust").session(dispatcher).with(csrf())
                        .param("adjustedAmount", "-1.00"))
                .andExpect(status().isBadRequest());
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult r = mvc.perform(formLogin().user(username).password("dock1234"))
                .andExpect(status().is3xxRedirection()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }
}
