package com.example.dockyard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 保养停用导致的预约处置记录（只追加：一次处置一行）。
 * 原时段 / 原月台类型在此固化快照；改期时 appointment 主表的 slot 被更新，
 * 但车牌、订单、货类、预约码等预约内容永不被覆盖，历史原值在本表可查。
 */
@Entity
@Table(name = "appointment_reschedule")
public class AppointmentReschedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_id", nullable = false)
    private Long appointmentId;

    @Column(name = "maintenance_id")
    private Long maintenanceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RescheduleAction action;

    @Column(name = "original_slot_start", nullable = false)
    private Instant originalSlotStart;

    @Column(name = "original_slot_end", nullable = false)
    private Instant originalSlotEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "original_dock_type", nullable = false)
    private DockType originalDockType;

    @Column(name = "new_slot_start")
    private Instant newSlotStart;

    @Column(name = "new_slot_end")
    private Instant newSlotEnd;

    @Column(nullable = false)
    private String reason;

    @Column(name = "acted_by", nullable = false)
    private Long actedBy;

    @Column(name = "acted_at", nullable = false, updatable = false)
    private Instant actedAt;

    @PrePersist
    void prePersist() {
        if (actedAt == null) {
            actedAt = BusinessTime.now();
        }
    }

    public Long getId() { return id; }
    public Long getAppointmentId() { return appointmentId; }
    public void setAppointmentId(Long appointmentId) { this.appointmentId = appointmentId; }
    public Long getMaintenanceId() { return maintenanceId; }
    public void setMaintenanceId(Long maintenanceId) { this.maintenanceId = maintenanceId; }
    public RescheduleAction getAction() { return action; }
    public void setAction(RescheduleAction action) { this.action = action; }
    public Instant getOriginalSlotStart() { return originalSlotStart; }
    public void setOriginalSlotStart(Instant originalSlotStart) { this.originalSlotStart = originalSlotStart; }
    public Instant getOriginalSlotEnd() { return originalSlotEnd; }
    public void setOriginalSlotEnd(Instant originalSlotEnd) { this.originalSlotEnd = originalSlotEnd; }
    public DockType getOriginalDockType() { return originalDockType; }
    public void setOriginalDockType(DockType originalDockType) { this.originalDockType = originalDockType; }
    public Instant getNewSlotStart() { return newSlotStart; }
    public void setNewSlotStart(Instant newSlotStart) { this.newSlotStart = newSlotStart; }
    public Instant getNewSlotEnd() { return newSlotEnd; }
    public void setNewSlotEnd(Instant newSlotEnd) { this.newSlotEnd = newSlotEnd; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getActedBy() { return actedBy; }
    public void setActedBy(Long actedBy) { this.actedBy = actedBy; }
    public Instant getActedAt() { return actedAt; }
}
