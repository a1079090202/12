package com.example.dockyard.service;

import com.example.dockyard.domain.*;
import com.example.dockyard.repo.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 异议处理（独立服务）：
 *  - 承运商对已核费单提起异议，提起瞬间把当时金额快照进 dispute.original_amount；
 *  - 调度员处理时只能二选一：
 *      维持（REJECTED）：fee.final_amount 与 original_amount 都不变；
 *      调整（ADJUSTED）：只写 fee.final_amount 与 dispute.adjusted_amount，
 *                        fee.original_amount、费率快照、分段明细一律保留不动。
 *  - 系统里没有任何代码路径会 UPDATE original_amount / fee_segment，旧记录不物理删除。
 */
@Service
public class DisputeService {

    private final DisputeRepository disputes;
    private final FeeSettlementRepository settlements;
    private final AppointmentRepository appointments;
    private final EventLogService events;
    private final YardClock clock;
    private final jakarta.persistence.EntityManager em;

    public DisputeService(DisputeRepository disputes, FeeSettlementRepository settlements,
                          AppointmentRepository appointments, EventLogService events, YardClock clock,
                          jakarta.persistence.EntityManager em) {
        this.disputes = disputes;
        this.settlements = settlements;
        this.appointments = appointments;
        this.events = events;
        this.clock = clock;
        this.em = em;
    }

    @Transactional
    @PreAuthorize("hasRole('CARRIER')")
    public Dispute raise(Long appointmentId, String reason, Long carrierId, Long actorId) {
        FeeSettlement fs = settlements.findByAppointmentId(appointmentId)
                .orElseThrow(() -> new BusinessRuleException("还没有核费单，暂不能提异议"));
        if (!fs.getCarrierId().equals(carrierId)) {
            throw new org.springframework.security.access.AccessDeniedException("只能对本承运商的核费单提异议");
        }
        if (reason == null || reason.strip().isBlank()) {
            throw new BusinessRuleException("异议原因不能为空");
        }
        boolean openExists = disputes.findByAppointmentIdOrderByRaisedAtDesc(appointmentId).stream()
                .anyMatch(d -> d.getStatus() == DisputeStatus.OPEN);
        if (openExists) {
            throw new BusinessRuleException("该单已有待处理的异议，请勿重复提交");
        }

        Dispute d = new Dispute();
        d.setAppointmentId(appointmentId);
        d.setCarrierId(carrierId);
        d.setReason(reason.strip());
        d.setStatus(DisputeStatus.OPEN);
        d.setOriginalAmount(fs.getFinalAmount());
        d.setRaisedBy(actorId);
        d.setRaisedAt(clock.now());
        try {
            disputes.save(d);
            em.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 并发重复提交撞上部分唯一索引 uq_dispute_open_per_appt
            throw new BusinessRuleException("该单已有待处理的异议，请勿重复提交");
        }

        fs.setStatus(FeeStatus.DISPUTED);
        settlements.save(fs);
        events.record(appointmentId, EventType.DISPUTE_RAISED, actorId, null,
                "承运商提起异议：" + reason.strip() + "；提起时金额 " + d.getOriginalAmount() + " 元");
        return d;
    }

    /** 调度驳回：维持原金额 */
    @Transactional
    @PreAuthorize("hasRole('DISPATCHER')")
    public Dispute reject(Long disputeId, String note, Long actorId) {
        Dispute d = open(disputeId);
        FeeSettlement fs = fee(d);

        d.setStatus(DisputeStatus.REJECTED);
        d.setResolutionNote(note);
        d.setResolvedBy(actorId);
        d.setResolvedAt(clock.now());
        // 明确不写 adjustedAmount；finalAmount 保持等于 originalAmount
        disputes.save(d);

        fs.setStatus(FeeStatus.UPHELD);
        // 刻意不改 fs.finalAmount / originalAmount
        settlements.save(fs);
        events.record(fs.getAppointmentId(), EventType.DISPUTE_REJECTED, actorId, null,
                "异议 #" + d.getId() + " 驳回，维持原金额 " + fs.getOriginalAmount() + " 元");
        return d;
    }

    /** 调度成立并调整金额：只动 final_amount */
    @Transactional
    @PreAuthorize("hasRole('DISPATCHER')")
    public Dispute adjust(Long disputeId, BigDecimal adjustedAmount, String note, Long actorId) {
        Dispute d = open(disputeId);
        FeeSettlement fs = fee(d);
        if (adjustedAmount == null || adjustedAmount.signum() < 0) {
            throw new BusinessRuleException("调整后金额不能为空且不能为负");
        }

        d.setStatus(DisputeStatus.ADJUSTED);
        d.setAdjustedAmount(adjustedAmount);
        d.setResolutionNote(note);
        d.setResolvedBy(actorId);
        d.setResolvedAt(clock.now());
        disputes.save(d);

        fs.setStatus(FeeStatus.ADJUSTED);
        fs.setFinalAmount(adjustedAmount);   // originalAmount 保持系统核算值不变
        settlements.save(fs);
        events.record(fs.getAppointmentId(), EventType.DISPUTE_ADJUSTED, actorId, null,
                "异议 #" + d.getId() + " 成立，金额 " + fs.getOriginalAmount()
                        + " 元调整为 " + adjustedAmount + " 元（原金额保留）");
        return d;
    }

    @Transactional(readOnly = true)
    public java.util.List<Dispute> openDisputes() {
        return disputes.findByStatusOrderByRaisedAtAsc(DisputeStatus.OPEN);
    }

    @Transactional(readOnly = true)
    public java.util.List<Dispute> historyOf(Long appointmentId) {
        return disputes.findByAppointmentIdOrderByRaisedAtDesc(appointmentId);
    }

    private Dispute open(Long id) {
        // 行锁：两个调度并发处理同一异议时串行，先到者改状态，后者拿到锁后看到已处理而拒绝
        Dispute d = disputes.lockById(id)
                .orElseThrow(() -> new BusinessRuleException("异议不存在"));
        if (d.getStatus() != DisputeStatus.OPEN) {
            throw new BusinessRuleException("该异议已处理，不能重复处理或覆盖结论");
        }
        return d;
    }

    private FeeSettlement fee(Dispute d) {
        return settlements.findByAppointmentId(d.getAppointmentId())
                .orElseThrow(() -> new BusinessRuleException("核费单缺失"));
    }
}
