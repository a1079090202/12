package com.example.dockyard.service;

import com.example.dockyard.domain.OperationEvent;
import com.example.dockyard.repo.AppUserRepository;
import com.example.dockyard.repo.OperationEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 操作时间线：把事件和操作人姓名组装出来（事件本身只存 actor_id）。 */
@Service
public class EventTimelineService {

    public record TimelineEntry(OperationEvent event, String actorName) {}

    private final OperationEventRepository events;
    private final AppUserRepository users;

    public EventTimelineService(OperationEventRepository events, AppUserRepository users) {
        this.events = events;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> ofAppointment(Long appointmentId) {
        return events.findByAppointmentIdOrderByOccurredAtAsc(appointmentId).stream()
                .map(e -> new TimelineEntry(e,
                        users.findById(e.getActorId())
                                .map(u -> u.getDisplayName() + "（" + u.getRole().name() + "）")
                                .orElse("用户#" + e.getActorId())))
                .toList();
    }
}
