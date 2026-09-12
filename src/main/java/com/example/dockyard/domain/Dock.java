package com.example.dockyard.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "dock")
public class Dock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "dock_type", nullable = false)
    private DockType dockType;

    @Column(nullable = false)
    private boolean active = true;

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public DockType getDockType() { return dockType; }
    public void setDockType(DockType dockType) { this.dockType = dockType; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
