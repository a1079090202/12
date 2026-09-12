package com.example.dockyard;

import com.example.dockyard.domain.*;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.DisputeRepository;
import com.example.dockyard.repo.FeeSettlementRepository;
import com.example.dockyard.service.BusinessRuleException;
import com.example.dockyard.service.DisputeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 场景：承运商对核费提异议，调度处理。
 *  - 驳回：维持原金额，fee 状态 UPHELD，原始金额不变；
 *  - 再次提异议并成立调整：只改 final_amount，original_amount 与费率快照保留；
 *  - 别的承运商不能对该单提异议；
 *  - 已处理的异议不能重复处理（原结论不可被抹掉）。
 * 样例数据里 SO-DONE-01（沪A00023，carrier1）已出场核费 60.00 元。
 */
class DisputeHandlingTest extends AbstractIntegrationTest {

    @Autowired DisputeService disputeService;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired FeeSettlementRepository feeSettlementRepository;
    @Autowired DisputeRepository disputeRepository;

    private Appointment seededExited() {
        return appointmentRepository.findByOrderNo("SO-DONE-01").orElseThrow();
    }

    @Test
    void reject_keeps_original_amount_then_adjust_only_changes_final_amount() {
        Appointment appt = seededExited();
        Long c1 = carrierIdOf("carrier1");

        FeeSettlement before = feeSettlementRepository.findByAppointmentId(appt.getId()).orElseThrow();
        assertThat(before.getOriginalAmount()).isEqualByComparingTo("60.00");
        assertThat(before.getFinalAmount()).isEqualByComparingTo("60.00");

        // 承运商提异议
        loginAs("carrier1");
        Dispute d1 = disputeService.raise(appt.getId(), "等待是月台故障导致", c1, userId("carrier1"));
        assertThat(feeSettlementRepository.findByAppointmentId(appt.getId()).orElseThrow().getStatus())
                .isEqualTo(FeeStatus.DISPUTED);

        // 调度驳回，维持原价
        loginAs("dispatcher");
        disputeService.reject(d1.getId(), "查监控属正常排队", userId("dispatcher"));
        FeeSettlement afterReject = feeSettlementRepository.findByAppointmentId(appt.getId()).orElseThrow();
        assertThat(afterReject.getStatus()).isEqualTo(FeeStatus.UPHELD);
        assertThat(afterReject.getOriginalAmount()).isEqualByComparingTo("60.00");
        assertThat(afterReject.getFinalAmount()).isEqualByComparingTo("60.00");
        assertThat(disputeRepository.findById(d1.getId()).orElseThrow().getStatus())
                .isEqualTo(DisputeStatus.REJECTED);
        // 已处理的异议不能再处理
        assertThatThrownBy(() -> disputeService.adjust(d1.getId(), new BigDecimal("1.00"),
                "试图改结论", userId("dispatcher")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已处理");

        // 承运商再次提异议，调度成立并调整为 30
        loginAs("carrier1");
        Dispute d2 = disputeService.raise(appt.getId(), "补充月台故障报修单", c1, userId("carrier1"));
        loginAs("dispatcher");
        disputeService.adjust(d2.getId(), new BigDecimal("30.00"), "部分责任在园区", userId("dispatcher"));

        FeeSettlement afterAdjust = feeSettlementRepository.findByAppointmentId(appt.getId()).orElseThrow();
        assertThat(afterAdjust.getStatus()).isEqualTo(FeeStatus.ADJUSTED);
        assertThat(afterAdjust.getFinalAmount()).isEqualByComparingTo("30.00");
        // 关键断言：原始金额没有被抹掉
        assertThat(afterAdjust.getOriginalAmount()).isEqualByComparingTo("60.00");
        Dispute saved2 = disputeRepository.findById(d2.getId()).orElseThrow();
        assertThat(saved2.getStatus()).isEqualTo(DisputeStatus.ADJUSTED);
        assertThat(saved2.getOriginalAmount()).isEqualByComparingTo("60.00");
        assertThat(saved2.getAdjustedAmount()).isEqualByComparingTo("30.00");
        assertThat(saved2.getResolvedBy()).isEqualTo(userId("dispatcher"));
    }

    @Test
    void other_carrier_cannot_dispute_and_empty_reason_rejected() {
        Appointment appt = seededExited();

        loginAs("carrier2");
        assertThatThrownBy(() -> disputeService.raise(
                appt.getId(), "不是我的单也想提", carrierIdOf("carrier2"), userId("carrier2")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("本承运商");

        loginAs("carrier1");
        assertThatThrownBy(() -> disputeService.raise(
                appt.getId(), "  ", carrierIdOf("carrier1"), userId("carrier1")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("异议原因不能为空");
    }
}
