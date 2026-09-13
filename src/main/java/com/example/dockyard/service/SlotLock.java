package com.example.dockyard.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 槽位事务级咨询锁：把同一 30 分钟槽的并发预约/插单/保养改期串行化，
 * 消除“先计数后插入/更新”的 TOCTOU 超卖竞态。
 * key 由槽位起点文本哈希得到（hashtextextended 为 PG 11+ 内置函数），
 * 锁随事务提交/回滚自动释放。
 */
@Component
public class SlotLock {

    private final EntityManager em;

    public SlotLock(EntityManager em) {
        this.em = em;
    }

    public void lock(Instant slotStart) {
        em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(?1, 0))")
                .setParameter(1, "appt-slot:" + slotStart.toString())
                .getSingleResult();
    }
}
