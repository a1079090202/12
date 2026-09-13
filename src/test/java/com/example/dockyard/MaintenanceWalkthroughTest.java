package com.example.dockyard;

import com.example.dockyard.domain.*;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.AppointmentRescheduleRepository;
import com.example.dockyard.repo.DockMaintenanceRepository;
import com.example.dockyard.repo.OperationEventRepository;
import com.example.dockyard.service.AppointmentService;
import com.example.dockyard.service.BusinessRuleException;
import com.example.dockyard.service.DockMaintenanceService;
import com.example.dockyard.service.GateService;
import com.example.dockyard.service.QueueAllocationService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 月台保养停用端到端走查（对应用户验收：一条停用带出两条受影响预约，
 * 看清单准不准、改期后容量和看板对不对得上）：
 *  - D5（唯一冷藏月台）明天 08:00–12:00 停用；
 *  - 窗内两条冷藏预约进受影响清单：一条改约待定（释放容量/禁止进场），一条改期 14:00；
 *  - 停用期间冷藏容量归零、普通不受影响；
 *  - 看板 D5 出现“保养中”标识；自动派台跳过停用月台，手选被服务端拒绝；
 *  - 取消停用后容量恢复。
 */
@AutoConfigureMockMvc
class MaintenanceWalkthroughTest extends AbstractIntegrationTest {

    @Autowired DockMaintenanceService maintenanceService;
    @Autowired AppointmentService appointmentService;
    @Autowired GateService gateService;
    @Autowired QueueAllocationService queueAllocationService;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired DockMaintenanceRepository maintenanceRepository;
    @Autowired AppointmentRescheduleRepository rescheduleRepository;
    @Autowired OperationEventRepository eventRepository;
    @Autowired YardClock yardClock;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final ZoneId ZONE = YardClock.ZONE;
    private final LocalDate day = LocalDate.now(ZONE).plusDays(1);

    @AfterEach
    void reset() {
        yardClock.setClock(Clock.system(ZONE));
    }

    private void freeze(String hm) {
        yardClock.setClock(Clock.fixed(
                ZonedDateTime.of(day, LocalTime.parse(hm), ZONE).toInstant(), ZONE));
    }

    private long dockId(String code) {
        return jdbcTemplate.queryForObject("select id from dock where code = ?", Long.class, code);
    }

    private AppointmentService.BookingRequest coldReq(String orderNo, String plate, String hm) {
        return new AppointmentService.BookingRequest(orderNo, plate, "司机", null,
                "冷冻肉类", DockType.COLD, day, hm);
    }

    /** 主流程：登记 → 受影响清单 → 容量归零 → 待定/改期 → 留痕与原值保留 */
    @Test
    void register_impact_list_pending_and_reschedule_with_audit() throws Exception {
        loginAs("carrier2");
        Long carrier2 = carrierIdOf("carrier2");
        // 冷藏每槽容量只有 D5 一个坑：两单分别落在 09:00 / 09:30
        Appointment a1 = appointmentService.book(
                coldReq("MT-A", "冷A0001", "09:00"), carrier2, userId("carrier2"));
        Appointment a2 = appointmentService.book(
                coldReq("MT-B", "冷A0002", "09:30"), carrier2, userId("carrier2"));

        loginAs("dispatcher");
        Long dispatcher = userId("dispatcher");
        String reason = "设备科安排半天例行保养";
        DockMaintenance m = maintenanceService.register(
                dockId("D5"), day, "08:00", "12:00", reason, dispatcher);

        // 1) 受影响清单恰好是这两条；窗外 / 其他类型的单不在内
        List<Appointment> impact = maintenanceService.impactList(m);
        assertThat(impact).extracting(Appointment::getId).containsExactly(a1.getId(), a2.getId());

        // 停用详情页真实渲染：受影响清单与车牌都在（模板只在渲染期解析）
        MvcResult login0 = mvc.perform(formLogin().user("dispatcher").password("dock1234"))
                .andExpect(status().is3xxRedirection()).andReturn();
        MockHttpSession dispatcherSession = (MockHttpSession) login0.getRequest().getSession(false);
        String detailHtml = mvc.perform(get("/maintenance/" + m.getId()).session(dispatcherSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(detailHtml).contains("冷A0001", "冷A0002", "受影响预约");

        // 2) 停用后冷藏容量归零，新冷藏单（含插单）无法约；普通月台容量仍是 4
        loginAs("carrier2");
        assertThatThrownBy(() -> appointmentService.book(
                coldReq("MT-C", "冷A0003", "10:00"), carrier2, userId("carrier2")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("没有可用的冷藏月台");
        loginAs("dispatcher");
        assertThatThrownBy(() -> appointmentService.override(
                coldReq("MT-C", "冷A0003", "10:00"), carrier2, dispatcher, "加急"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("无法插单");
        assertThat(maintenanceService.availableDockCount(DockType.STANDARD,
                yardClock.parseDateTime(day, "10:00"), yardClock.parseDateTime(day, "10:30")))
                .isEqualTo(4);

        // 3) 第 1 条标记改约待定：状态变更、释放容量、留痕
        maintenanceService.markPending(a1.getId(), m.getId(), "先挂起等车主回复", dispatcher);
        a1 = appointmentRepository.findById(a1.getId()).orElseThrow();
        assertThat(a1.getStatus()).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
        assertThat(appointmentRepository.countActiveBySlotStartAndDockType(
                yardClock.parseDateTime(day, "09:00"), DockType.COLD)).isZero();
        // 门卫不放行待定单
        loginAs("guard");
        final String a1Code = a1.getCode();
        assertThatThrownBy(() -> gateService.gateIn(a1Code, userId("guard")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("改约待定");
        var pendingEvents = eventRepository.findByAppointmentIdOrderByOccurredAtAsc(a1.getId());
        assertThat(pendingEvents).extracting(e -> e.getEventType().name()).contains("MAINT_PENDING");
        assertThat(pendingEvents.get(pendingEvents.size() - 1).getDetail()).contains(reason);

        // 4) 第 2 条改期到 14:00（保养窗外，冷藏容量恢复为 1）
        loginAs("dispatcher");
        maintenanceService.reschedule(a2.getId(), m.getId(), day, "14:00",
                "保养后同类型唯一空档", dispatcher);
        a2 = appointmentRepository.findById(a2.getId()).orElseThrow();
        assertThat(a2.getSlotStart()).isEqualTo(yardClock.parseDateTime(day, "14:00"));
        // 预约内容不被覆盖
        assertThat(a2.getCode()).startsWith("YY");
        assertThat(a2.getOrderNo()).isEqualTo("MT-B");
        assertThat(a2.getPlateNo()).isEqualTo("冷A0002");
        assertThat(a2.getCargoType()).isEqualTo("冷冻肉类");
        assertThat(a2.getStatus()).isEqualTo(AppointmentStatus.BOOKED);
        var recs = rescheduleRepository.findByAppointmentIdOrderByActedAtAsc(a2.getId());
        assertThat(recs).hasSize(1);
        var rec = recs.get(0);
        assertThat(rec.getAction()).isEqualTo(RescheduleAction.RESCHEDULED);
        assertThat(rec.getOriginalSlotStart()).isEqualTo(yardClock.parseDateTime(day, "09:30"));
        assertThat(rec.getNewSlotStart()).isEqualTo(yardClock.parseDateTime(day, "14:00"));
        assertThat(rec.getReason()).isEqualTo("保养后同类型唯一空档");
        assertThat(rec.getActedBy()).isEqualTo(dispatcher);
        assertThat(eventRepository.findByAppointmentIdOrderByOccurredAtAsc(a2.getId()))
                .extracting(e -> e.getEventType().name()).contains("RESCHEDULED");

        // 5) 容量校验：待定单想改到已被 a2 占满的 14:00 冷藏槽被拒，改 15:00 成功
        final Long a1Id = a1.getId();
        final Long mId = m.getId();
        assertThatThrownBy(() -> maintenanceService.reschedule(
                a1Id, mId, day, "14:00", "挤一挤", dispatcher))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("容量 1 已满");
        maintenanceService.reschedule(a1Id, mId, day, "15:00", "车主确认", dispatcher);
        a1 = appointmentRepository.findById(a1Id).orElseThrow();
        assertThat(a1.getStatus()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(a1.getSlotStart()).isEqualTo(yardClock.parseDateTime(day, "15:00"));
    }

    /** 看板标识 + 叫号派台跳过停用月台（自动跳过 / 手选拒绝） */
    @Test
    void dashboard_marks_dock_and_allocation_skips_maintenance() throws Exception {
        freeze("10:30");
        // 先于停用登记预约：一辆普通（D3 窗内）、一辆冷藏（D5 窗内）
        loginAs("carrier1");
        Appointment std = appointmentService.book(
                new AppointmentService.BookingRequest("MT-STD", "普A0001", "司机", null,
                        "日化百货", DockType.STANDARD, day, "10:30"),
                carrierIdOf("carrier1"), userId("carrier1"));
        loginAs("carrier2");
        Appointment cold = appointmentService.book(
                coldReq("MT-COLD", "冷B0001", "11:00"), carrierIdOf("carrier2"), userId("carrier2"));

        loginAs("dispatcher");
        Long dispatcher = userId("dispatcher");
        long d5 = dockId("D5");
        long d3 = dockId("D3");
        maintenanceService.register(d5, day, "08:00", "12:00", "冷藏台保养", dispatcher);
        maintenanceService.register(d3, day, "10:00", "12:00", "普通台保养", dispatcher);

        // 看板：D5、D3 卡片带 maint 样式与“保养中”
        MvcResult login = mvc.perform(formLogin().user("dispatcher").password("dock1234"))
                .andExpect(status().is3xxRedirection()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        String dash = mvc.perform(get("/").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(dash).contains("保养中").contains("maint");

        // 两辆车到场进场后派台：普通车自动派台必须跳过 D3，落到 D1/D2/D4
        loginAs("guard");
        gateService.gateIn(std.getCode(), userId("guard"));
        gateService.gateIn(cold.getCode(), userId("guard"));
        loginAs("dispatcher");
        Appointment called = queueAllocationService.callToDock(std.getId(), null, dispatcher);
        assertThat(called.getAssignedDockId()).isNotEqualTo(d3);

        // 冷藏车仍在等候：手选停用中的 D5 被服务端拒绝，自动派台同样无台可派
        assertThatThrownBy(() -> queueAllocationService.callToDock(cold.getId(), d5, dispatcher))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("保养停用中");
        assertThatThrownBy(() -> queueAllocationService.callToDock(cold.getId(), null, dispatcher))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("没有空闲且未停用的冷藏月台");
    }

    /** 取消停用后容量恢复、看板标识消失 */
    @Test
    void cancel_maintenance_restores_capacity() throws Exception {
        freeze("09:00");
        loginAs("dispatcher");
        Long dispatcher = userId("dispatcher");
        long d5 = dockId("D5");
        DockMaintenance m = maintenanceService.register(
                d5, day, "08:00", "12:00", "临时检修", dispatcher);
        assertThat(maintenanceService.availableDockCount(DockType.COLD,
                yardClock.parseDateTime(day, "09:00"), yardClock.parseDateTime(day, "09:30")))
                .isZero();

        maintenanceService.cancel(m.getId(), "设备科提前完成", dispatcher);
        assertThat(maintenanceService.availableDockCount(DockType.COLD,
                yardClock.parseDateTime(day, "09:00"), yardClock.parseDateTime(day, "09:30")))
                .isEqualTo(1);

        MvcResult login = mvc.perform(formLogin().user("dispatcher").password("dock1234"))
                .andExpect(status().is3xxRedirection()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        String dash = mvc.perform(get("/").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(dash).doesNotContain("保养中");
    }
}
