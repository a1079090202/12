package com.example.dockyard.service;

import com.example.dockyard.domain.EventType;
import com.example.dockyard.domain.OperationEvent;
import com.example.dockyard.repo.OperationEventRepository;
import org.springframework.stereotype.Service;

/** 全流程操作留痕：每一步保留操作人与发生时间，只追加，永不修改/删除。 */
@Service
public class EventLogService {

    private final OperationEventRepository eventRepository;
    private final YardClock clock;

    public EventLogService(OperationEventRepository eventRepository, YardClock clock) {
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    public void record(Long appointmentId, EventType type, Long actorId, Long dockId, String detail) {
        eventRepository.save(new OperationEvent(
                appointmentId, type, actorId, dockId, detail, clock.now()));
    }
}
