package com.example.dockyard.repo;

import com.example.dockyard.domain.FeeSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FeeSegmentRepository extends JpaRepository<FeeSegment, Long> {
    List<FeeSegment> findBySettlementIdOrderBySegmentDateAsc(Long settlementId);
}
