package com.example.dockyard.repo;

import com.example.dockyard.domain.Dock;
import com.example.dockyard.domain.DockType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DockRepository extends JpaRepository<Dock, Long> {
    List<Dock> findByActiveTrueOrderByCode();
    List<Dock> findByActiveTrueAndDockTypeOrderByCode(DockType dockType);
}
