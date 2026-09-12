package com.example.dockyard.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "fee_settlement")
public class FeeSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_id", nullable = false, unique = true)
    private Long appointmentId;

    @Column(name = "carrier_id", nullable = false)
    private Long carrierId;

    @Column(name = "plate_no", nullable = false)
    private String plateNo;

    @Column(name = "free_minutes", nullable = false)
    private int freeMinutes;

    @Column(name = "wait_minutes", nullable = false)
    private int waitMinutes;

    @Column(name = "chargeable_minutes", nullable = false)
    private int chargeableMinutes;

    @Column(name = "rate_snapshot", nullable = false, precision = 12, scale = 2)
    private BigDecimal rateSnapshot;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    /** 系统原始核算金额——异议处理流程的任何环节都不得修改 */
    @Column(name = "original_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal originalAmount;

    /** 当前生效金额：维持=原值，调整=新值 */
    @Column(name = "final_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal finalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FeeStatus status = FeeStatus.CONFIRMED;

    @Column(name = "generated_by")
    private Long generatedBy;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    public Long getId() { return id; }
    public Long getAppointmentId() { return appointmentId; }
    public void setAppointmentId(Long appointmentId) { this.appointmentId = appointmentId; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public int getFreeMinutes() { return freeMinutes; }
    public void setFreeMinutes(int freeMinutes) { this.freeMinutes = freeMinutes; }
    public int getWaitMinutes() { return waitMinutes; }
    public void setWaitMinutes(int waitMinutes) { this.waitMinutes = waitMinutes; }
    public int getChargeableMinutes() { return chargeableMinutes; }
    public void setChargeableMinutes(int chargeableMinutes) { this.chargeableMinutes = chargeableMinutes; }
    public BigDecimal getRateSnapshot() { return rateSnapshot; }
    public void setRateSnapshot(BigDecimal rateSnapshot) { this.rateSnapshot = rateSnapshot; }
    public LocalDate getRateDate() { return rateDate; }
    public void setRateDate(LocalDate rateDate) { this.rateDate = rateDate; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(BigDecimal originalAmount) { this.originalAmount = originalAmount; }
    public BigDecimal getFinalAmount() { return finalAmount; }
    public void setFinalAmount(BigDecimal finalAmount) { this.finalAmount = finalAmount; }
    public FeeStatus getStatus() { return status; }
    public void setStatus(FeeStatus status) { this.status = status; }
    public Long getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(Long generatedBy) { this.generatedBy = generatedBy; }
    public Instant getGeneratedAt() { return generatedAt; }
}
