package com.example.dockyard.repo;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.domain.AppointmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    Optional<Appointment> findByCode(String code);

    Optional<Appointment> findByOrderNo(String orderNo);

    /** 派台时对预约行加悲观锁：两个调度员同时操作同一单 / 同一链路时串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appointment a where a.id = :id")
    Optional<Appointment> lockById(@Param("id") Long id);

    /** 门卫扫码进场时按预约码对预约行加悲观锁，串行化同码的并发进场 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appointment a where a.code = :code")
    Optional<Appointment> lockByCode(@Param("code") String code);

    /** 派台前对月台行加锁（由服务层调用 native/实体锁），配合部分唯一索引双保险 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dock d where d.id = :id")
    Optional<com.example.dockyard.domain.Dock> lockDockById(@Param("id") Long id);

    /**
     * 统计某 30 分钟时段内的有效预约数（插单也计入）。
     * 时段重叠即视为占用同一容量格子，此处按精确的 slot_start 对齐统计，
     * 服务层保证 slot_start 一律按 30 分钟整点切槽。
     */
    long countBySlotStartAndStatusNot(Instant slotStart, AppointmentStatus status);

    @Query("""
            select a from Appointment a
            where a.status in (com.example.dockyard.domain.AppointmentStatus.GATED_IN,
                               com.example.dockyard.domain.AppointmentStatus.CALLED)
            order by case a.status
                when com.example.dockyard.domain.AppointmentStatus.GATED_IN then 0
                else 1 end,
                a.arrivedAt
            """)
    List<Appointment> findWaitingOrdered();

    List<Appointment> findByStatusOrderBySlotStart(AppointmentStatus status);

    @Query("""
            select a from Appointment a
            where a.status in (com.example.dockyard.domain.AppointmentStatus.GATED_IN,
                               com.example.dockyard.domain.AppointmentStatus.CALLED,
                               com.example.dockyard.domain.AppointmentStatus.DOCKED,
                               com.example.dockyard.domain.AppointmentStatus.UNLOADING,
                               com.example.dockyard.domain.AppointmentStatus.COMPLETED)
            """)
    List<Appointment> findAllInYard();

    @Query("""
            select a from Appointment a
            where a.exitedAt >= :dayStart and a.exitedAt < :dayEnd
            order by a.exitedAt
            """)
    List<Appointment> findExitedBetween(@Param("dayStart") Instant dayStart,
                                        @Param("dayEnd") Instant dayEnd);

    @Query("""
            select a from Appointment a
            where a.slotStart >= :dayStart and a.slotStart < :dayEnd
            order by a.slotStart
            """)
    List<Appointment> findBySlotBetween(@Param("dayStart") Instant dayStart,
                                        @Param("dayEnd") Instant dayEnd);

    List<Appointment> findByCarrierIdOrderByCreatedAtDesc(Long carrierId);

    @Query("""
            select a from Appointment a
            where a.assignedDockId = :dockId
              and a.status in (com.example.dockyard.domain.AppointmentStatus.CALLED,
                               com.example.dockyard.domain.AppointmentStatus.DOCKED,
                               com.example.dockyard.domain.AppointmentStatus.UNLOADING,
                               com.example.dockyard.domain.AppointmentStatus.COMPLETED)
            """)
    List<Appointment> findOccupyingDock(@Param("dockId") Long dockId);

    @Query("""
            select a from Appointment a
            where a.plateNo = :plateNo
              and a.status in (com.example.dockyard.domain.AppointmentStatus.GATED_IN,
                               com.example.dockyard.domain.AppointmentStatus.CALLED,
                               com.example.dockyard.domain.AppointmentStatus.DOCKED,
                               com.example.dockyard.domain.AppointmentStatus.UNLOADING,
                               com.example.dockyard.domain.AppointmentStatus.COMPLETED)
            """)
    List<Appointment> findPlateInYard(@Param("plateNo") String plateNo);
}
