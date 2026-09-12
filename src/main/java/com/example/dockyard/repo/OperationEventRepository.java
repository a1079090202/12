package com.example.dockyard.repo;

import com.example.dockyard.domain.OperationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OperationEventRepository extends JpaRepository<OperationEvent, Long> {
    List<OperationEvent> findByAppointmentIdOrderByOccurredAtAsc(Long appointmentId);
}
