package com.example.dockyard.repo;

import com.example.dockyard.domain.Carrier;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CarrierRepository extends JpaRepository<Carrier, Long> {
    Optional<Carrier> findByCode(String code);
}
