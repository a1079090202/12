package com.example.dockyard.web;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.security.LoginUser;
import com.example.dockyard.service.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/** 预约详情：状态时间线、操作留痕、核费与异议。承运商只能看本司单。 */
@Controller
@RequestMapping("/appointments")
public class AppointmentDetailController {

    private final AppointmentRepository appointments;
    private final EventTimelineService timeline;
    private final FeeService fees;
    private final DisputeService disputes;
    private final ReferenceData ref;
    private final YardClock clock;

    public AppointmentDetailController(AppointmentRepository appointments, EventTimelineService timeline,
                                       FeeService fees, DisputeService disputes,
                                       ReferenceData ref, YardClock clock) {
        this.appointments = appointments;
        this.timeline = timeline;
        this.fees = fees;
        this.disputes = disputes;
        this.ref = ref;
        this.clock = clock;
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Appointment appt = appointments.findById(id)
                .orElseThrow(() -> new BusinessRuleException("预约不存在"));
        LoginUser me = CurrentUser.get();
        if (me.getRole() == com.example.dockyard.domain.Role.CARRIER
                && !appt.getCarrierId().equals(me.getCarrierId())) {
            throw new org.springframework.security.access.AccessDeniedException("只能查看本承运商的预约");
        }

        var settlement = fees.peekByAppointment(id);
        model.addAttribute("appt", appt);
        model.addAttribute("events", timeline.ofAppointment(id));
        model.addAttribute("settlement", settlement.orElse(null));
        model.addAttribute("segments", settlement
                .map(s -> fees.segments(s.getId())).orElse(java.util.List.of()));
        model.addAttribute("disputes", disputes.historyOf(id));
        model.addAttribute("ref", ref);
        model.addAttribute("clock", clock);
        model.addAttribute("me", me);
        return "appointment/detail";
    }
}
