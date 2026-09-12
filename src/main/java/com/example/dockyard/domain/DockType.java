package com.example.dockyard.domain;

/** 月台类型 / 货物所需月台类型 */
public enum DockType {
    STANDARD("普通月台"),
    COLD("冷藏月台"),
    OVERSIZE("大件月台");

    private final String label;

    DockType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
