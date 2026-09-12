package com.example.dockyard.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "fee_segment")
public class FeeSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "segment_date", nullable = false)
    private LocalDate segmentDate;

    @Column(nullable = false)
    private int minutes;

    @Column(name = "rate_per_hour", nullable = false, precision = 12, scale = 2)
    private BigDecimal ratePerHour;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    public FeeSegment() {}

    public FeeSegment(Long settlementId, LocalDate segmentDate, int minutes,
                      BigDecimal ratePerHour, BigDecimal amount) {
        this.settlementId = settlementId;
        this.segmentDate = segmentDate;
        this.minutes = minutes;
        this.ratePerHour = ratePerHour;
        this.amount = amount;
    }

    public Long getId() { return id; }
    public Long getSettlementId() { return settlementId; }
    public LocalDate getSegmentDate() { return segmentDate; }
    public int getMinutes() { return minutes; }
    public BigDecimal getRatePerHour() { return ratePerHour; }
    public BigDecimal getAmount() { return amount; }
}
