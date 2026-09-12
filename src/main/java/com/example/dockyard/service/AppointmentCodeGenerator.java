package com.example.dockyard.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 预约码生成：全站唯一实现，运行时预约与启动播种共用。
 * 格式 YY + 上海槽位日 yyMMdd + 数据库序列（至少 6 位，不足补零，超长不截断）。
 * 序列由数据库 sequence 分配，多实例/多线程并发不重号；
 * 不再对序列取模（旧实现 seq % 10000 在单日超 1 万单时会与同日旧码撞号）。
 */
@Component
public class AppointmentCodeGenerator {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private final EntityManager em;

    public AppointmentCodeGenerator(EntityManager em) {
        this.em = em;
    }

    /** 按预约所属槽位的上海自然日生成预约码 */
    public String next(Instant slotStart) {
        long seq = ((Number) em.createNativeQuery("select nextval('appt_code_seq')")
                .getSingleResult()).longValue();
        String dayPart = LocalDate.ofInstant(slotStart, YardClock.ZONE).format(DAY);
        return "YY" + dayPart + String.format("%06d", seq);
    }
}
