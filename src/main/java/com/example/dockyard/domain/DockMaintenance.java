package com.example.dockyard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 月台保养停用登记。业务记录不做物理删除（约定同 V1）：
 * SCHEDULED 生效中 -> CANCELLED 逻辑取消（容量即时恢复）。
 */
@Entity
@Table(name = "dock_maintenance")
public class DockMaintenance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dock_id", nullable = false)
    private Long dockId;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private String status = "SCHEDULED";

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = BusinessTime.now();
        }
    }

    public boolean isScheduled() {
        return "SCHEDULED".equals(status);
    }

    /** 给定时刻是否落在停用窗内（半开区间 [start, end)） */
    public boolean covers(Instant instant) {
        return isScheduled()
                && !instant.isBefore(windowStart)
                && instant.isBefore(windowEnd);
    }

    public Long getId() { return id; }
    public Long getDockId() { return dockId; }
    public void setDockId(Long dockId) { this.dockId = dockId; }
    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getCancelledBy() { return cancelledBy; }
    public void setCancelledBy(Long cancelledBy) { this.cancelledBy = cancelledBy; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
}
