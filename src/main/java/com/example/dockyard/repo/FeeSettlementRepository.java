package com.example.dockyard.repo;

import com.example.dockyard.domain.FeeSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FeeSettlementRepository extends JpaRepository<FeeSettlement, Long> {
    Optional<FeeSettlement> findByAppointmentId(Long appointmentId);

    List<FeeSettlement> findByGeneratedAtBetweenOrderByGeneratedAtAsc(Instant start, Instant end);
}
