package com.example.dockyard.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "appointment")
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "carrier_id", nullable = false)
    private Long carrierId;

    @Column(name = "order_no", nullable = false)
    private String orderNo;

    @Column(name = "plate_no", nullable = false)
    private String plateNo;

    @Column(name = "driver_name", nullable = false)
    private String driverName;

    @Column(name = "driver_phone")
    private String driverPhone;

    @Column(name = "cargo_type", nullable = false)
    private String cargoType;

    @Enumerated(EnumType.STRING)
    @Column(name = "dock_type", nullable = false)
    private DockType dockType;

    @Column(name = "slot_start", nullable = false)
    private Instant slotStart;

    @Column(name = "slot_end", nullable = false)
    private Instant slotEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppointmentStatus status = AppointmentStatus.BOOKED;

    @Column(name = "override_reason")
    private String overrideReason;

    @Column(name = "overridden_by")
    private Long overriddenBy;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "early_arrival", nullable = false)
    private boolean earlyArrival = false;

    @Column(name = "late_flag", nullable = false)
    private boolean lateFlag = false;

    @Column(name = "late_minutes", nullable = false)
    private int lateMinutes = 0;

    @Column(name = "called_at")
    private Instant calledAt;

    @Column(name = "assigned_dock_id")
    private Long assignedDockId;

    @Column(name = "docked_at")
    private Instant dockedAt;

    @Column(name = "unload_start_at")
    private Instant unloadStartAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "exited_at")
    private Instant exitedAt;

    @Column(name = "wait_minutes")
    private Integer waitMinutes;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = BusinessTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = BusinessTime.now();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public String getDriverPhone() { return driverPhone; }
    public void setDriverPhone(String driverPhone) { this.driverPhone = driverPhone; }
    public String getCargoType() { return cargoType; }
    public void setCargoType(String cargoType) { this.cargoType = cargoType; }
    public DockType getDockType() { return dockType; }
    public void setDockType(DockType dockType) { this.dockType = dockType; }
    public Instant getSlotStart() { return slotStart; }
    public void setSlotStart(Instant slotStart) { this.slotStart = slotStart; }
    public Instant getSlotEnd() { return slotEnd; }
    public void setSlotEnd(Instant slotEnd) { this.slotEnd = slotEnd; }
    public AppointmentStatus getStatus() { return status; }
    public void setStatus(AppointmentStatus status) { this.status = status; }
    public String getOverrideReason() { return overrideReason; }
    public void setOverrideReason(String overrideReason) { this.overrideReason = overrideReason; }
    public Long getOverriddenBy() { return overriddenBy; }
    public void setOverriddenBy(Long overriddenBy) { this.overriddenBy = overriddenBy; }
    public Instant getArrivedAt() { return arrivedAt; }
    public void setArrivedAt(Instant arrivedAt) { this.arrivedAt = arrivedAt; }
    public boolean isEarlyArrival() { return earlyArrival; }
    public void setEarlyArrival(boolean earlyArrival) { this.earlyArrival = earlyArrival; }
    public boolean isLateFlag() { return lateFlag; }
    public void setLateFlag(boolean lateFlag) { this.lateFlag = lateFlag; }
    public int getLateMinutes() { return lateMinutes; }
    public void setLateMinutes(int lateMinutes) { this.lateMinutes = lateMinutes; }
    public Instant getCalledAt() { return calledAt; }
    public void setCalledAt(Instant calledAt) { this.calledAt = calledAt; }
    public Long getAssignedDockId() { return assignedDockId; }
    public void setAssignedDockId(Long assignedDockId) { this.assignedDockId = assignedDockId; }
    public Instant getDockedAt() { return dockedAt; }
    public void setDockedAt(Instant dockedAt) { this.dockedAt = dockedAt; }
    public Instant getUnloadStartAt() { return unloadStartAt; }
    public void setUnloadStartAt(Instant unloadStartAt) { this.unloadStartAt = unloadStartAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getExitedAt() { return exitedAt; }
    public void setExitedAt(Instant exitedAt) { this.exitedAt = exitedAt; }
    public Integer getWaitMinutes() { return waitMinutes; }
    public void setWaitMinutes(Integer waitMinutes) { this.waitMinutes = waitMinutes; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
