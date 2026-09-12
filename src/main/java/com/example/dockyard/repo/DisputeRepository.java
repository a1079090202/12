package com.example.dockyard.repo;

import com.example.dockyard.domain.Dispute;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    List<Dispute> findByStatusOrderByRaisedAtAsc(com.example.dockyard.domain.DisputeStatus status);
    List<Dispute> findByAppointmentIdOrderByRaisedAtDesc(Long appointmentId);
    List<Dispute> findByCarrierIdOrderByRaisedAtDesc(Long carrierId);

    /** 处理异议时对异议行加悲观锁：两个调度并发处理同一单时串行，结论不可被并发覆盖 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dispute d where d.id = :id")
    Optional<Dispute> lockById(@Param("id") Long id);
}
