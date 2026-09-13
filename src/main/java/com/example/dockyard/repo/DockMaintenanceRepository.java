package com.example.dockyard.repo;

import com.example.dockyard.domain.DockMaintenance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DockMaintenanceRepository extends JpaRepository<DockMaintenance, Long> {

    /** 取消停用时对登记行加锁，防止并发“取消 + 受影响单处置”互相穿透 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from DockMaintenance m where m.id = :id")
    Optional<DockMaintenance> lockById(@Param("id") Long id);

    /** 与 [start,end) 半开区间重叠、仍生效（SCHEDULED）的停用登记 */
    @Query("""
            select m from DockMaintenance m
            where m.status = 'SCHEDULED'
              and m.windowStart < :end
              and m.windowEnd > :start
            order by m.windowStart
            """)
    List<DockMaintenance> findActiveOverlapping(@Param("start") Instant start,
                                                @Param("end") Instant end);

    /** 覆盖某一时刻（windowStart <= t < windowEnd）的生效停用，看板/派台用 */
    @Query("""
            select m from DockMaintenance m
            where m.status = 'SCHEDULED'
              and m.windowStart <= :at
              and m.windowEnd > :at
            """)
    List<DockMaintenance> findActiveAt(@Param("at") Instant at);

    List<DockMaintenance> findAllByOrderByWindowStartDesc();
}
