package com.example.dockyard.service;

import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * 全站唯一的业务时钟：所有业务时间一律按 Asia/Shanghai 解释。
 * 数据库存 timestamptz（Instant 绝对时刻），展示与“当日费率/今日”口径走上海时区。
 * 测试可注入固定时钟。
 */
@Component
public class YardClock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private volatile Clock clock = Clock.system(ZONE);

    /** 仅供测试替换时钟 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return Instant.now(clock);
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    public LocalDate dateOf(Instant instant) {
        return LocalDate.ofInstant(instant, ZONE);
    }

    /** 某上海自然日 00:00 对应的绝对时刻 */
    public Instant dayStart(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    /** 某上海自然日次日 00:00（半开区间上界） */
    public Instant dayEnd(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    /** 由上海日期与 "HH:mm" 解析绝对时刻（分钟） */
    public Instant parseDateTime(LocalDate date, String hm) {
        LocalTime time = LocalTime.parse(hm);
        return ZonedDateTime.of(date, time, ZONE).toInstant();
    }

    /** 把任意时刻向下取整到 30 分钟槽位起点（上海墙钟口径） */
    public Instant truncateToSlot(Instant instant, int slotMinutes) {
        ZonedDateTime z = instant.atZone(ZONE);
        int minute = z.getMinute();
        int floored = (minute / slotMinutes) * slotMinutes;
        return z.withMinute(floored).withSecond(0).withNano(0).toInstant();
    }

    public long minutesBetween(Instant from, Instant to) {
        return Duration.between(from, to).toMinutes();
    }

    public String formatDateTime(Instant instant) {
        return instant == null ? "" : DT.format(instant.atZone(ZONE));
    }

    public String formatTime(Instant instant) {
        return instant == null ? "" : HM.format(instant.atZone(ZONE));
    }
}
