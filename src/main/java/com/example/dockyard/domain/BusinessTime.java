package com.example.dockyard.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 实体审计时间戳与业务时钟之间的桥。
 * JPA 实体监听器（@PrePersist/@PreUpdate）不在 Spring 容器内，拿不到 YardClock，
 * 因此由 YardClock 在构造/替换时钟时把同一业务时钟安装到这里；
 * 实体上的 created_at / updated_at / generated_at / raised_at 一律经本类取时间，
 * 与核费、跨日等业务口径使用同一个时钟（测试冻结时一并冻结）。
 */
public final class BusinessTime {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static volatile Clock clock = Clock.system(ZONE);

    private BusinessTime() {
    }

    /** 由 YardClock 调用：安装当前业务时钟（含测试固定时钟） */
    public static void install(Clock businessClock) {
        clock = businessClock;
    }

    public static Instant now() {
        return Instant.now(clock);
    }
}
