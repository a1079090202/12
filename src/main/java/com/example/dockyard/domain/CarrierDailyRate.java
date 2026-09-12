package com.example.dockyard.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "carrier_daily_rate")
public class CarrierDailyRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "carrier_id", nullable = false)
    private Long carrierId;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(name = "rate_per_hour", nullable = false, precision = 12, scale = 2)
    private BigDecimal ratePerHour;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public LocalDate getRateDate() { return rateDate; }
    public void setRateDate(LocalDate rateDate) { this.rateDate = rateDate; }
    public BigDecimal getRatePerHour() { return ratePerHour; }
    public void setRatePerHour(BigDecimal ratePerHour) { this.ratePerHour = ratePerHour; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
