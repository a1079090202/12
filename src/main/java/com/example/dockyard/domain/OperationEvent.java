package com.example.dockyard.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "operation_event")
public class OperationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_id", nullable = false)
    private Long appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "dock_id")
    private Long dockId;

    private String detail;

    public OperationEvent() {}

    public OperationEvent(Long appointmentId, EventType eventType, Long actorId,
                          Long dockId, String detail, Instant occurredAt) {
        this.appointmentId = appointmentId;
        this.eventType = eventType;
        this.actorId = actorId;
        this.dockId = dockId;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public Long getAppointmentId() { return appointmentId; }
    public EventType getEventType() { return eventType; }
    public Long getActorId() { return actorId; }
    public Instant getOccurredAt() { return occurredAt; }
    public Long getDockId() { return dockId; }
    public String getDetail() { return detail; }
}
