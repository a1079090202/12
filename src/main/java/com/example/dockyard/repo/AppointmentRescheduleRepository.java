package com.example.dockyard.repo;

import com.example.dockyard.domain.AppointmentReschedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppointmentRescheduleRepository extends JpaRepository<AppointmentReschedule, Long> {

    List<AppointmentReschedule> findByAppointmentIdOrderByActedAtAsc(Long appointmentId);

    List<AppointmentReschedule> findByMaintenanceIdOrderByActedAtAsc(Long maintenanceId);
}
