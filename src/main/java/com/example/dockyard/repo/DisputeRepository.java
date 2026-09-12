package com.example.dockyard.repo;

import com.example.dockyard.domain.Dispute;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {
    List<Dispute> findByStatusOrderByRaisedAtAsc(com.example.dockyard.domain.DisputeStatus status);
    List<Dispute> findByAppointmentIdOrderByRaisedAtDesc(Long appointmentId);
    List<Dispute> findByCarrierIdOrderByRaisedAtDesc(Long carrierId);
}
