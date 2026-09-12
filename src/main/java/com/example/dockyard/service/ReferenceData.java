package com.example.dockyard.service;

import com.example.dockyard.domain.Carrier;
import com.example.dockyard.domain.Dock;
import com.example.dockyard.repo.CarrierRepository;
import com.example.dockyard.repo.DockRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 页面展示用的名称字典（每次从会话内读，量很小：6 个月台、少数承运商） */
@Component
public class ReferenceData {

    private final CarrierRepository carriers;
    private final DockRepository docks;

    public ReferenceData(CarrierRepository carriers, DockRepository docks) {
        this.carriers = carriers;
        this.docks = docks;
    }

    public Map<Long, Carrier> carrierMap() {
        return carriers.findAll().stream().collect(Collectors.toMap(Carrier::getId, Function.identity()));
    }

    public Map<Long, Dock> dockMap() {
        return docks.findByActiveTrueOrderByCode().stream()
                .collect(Collectors.toMap(Dock::getId, Function.identity()));
    }

    public String carrierName(Long id) {
        return carriers.findById(id).map(Carrier::getName).orElse("#" + id);
    }

    public String dockCode(Long id) {
        if (id == null) {
            return "-";
        }
        return docks.findById(id).map(Dock::getCode).orElse("#" + id);
    }
}
