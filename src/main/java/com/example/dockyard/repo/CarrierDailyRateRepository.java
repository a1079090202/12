package com.example.dockyard.repo;

import com.example.dockyard.domain.CarrierDailyRate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CarrierDailyRateRepository extends JpaRepository<CarrierDailyRate, Long> {
    Optional<CarrierDailyRate> findByCarrierIdAndRateDate(Long carrierId, LocalDate rateDate);

    List<CarrierDailyRate> findByRateDateBetweenOrderByRateDateAsc(LocalDate from, LocalDate to);
}
