package com.example.dockyard.web;

import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.service.GateService;
import com.example.dockyard.service.ReferenceData;
import com.example.dockyard.service.YardClock;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 门卫：输入预约码办理进场，看到在场车辆与今日预约。 */
@Controller
@Validated
@RequestMapping("/gate")
public class GateController {

    private final GateService gate;
    private final AppointmentRepository appointments;
    private final ReferenceData ref;
    private final YardClock clock;

    public GateController(GateService gate, AppointmentRepository appointments,
                          ReferenceData ref, YardClock clock) {
        this.gate = gate;
        this.appointments = appointments;
        this.ref = ref;
        this.clock = clock;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("todayList", appointments.findBySlotBetween(
                clock.dayStart(clock.today()), clock.dayEnd(clock.today())));
        model.addAttribute("inYard", appointments.findAllInYard());
        model.addAttribute("ref", ref);
        model.addAttribute("clock", clock);
        model.addAttribute("result", null);
        return "gate/page";
    }

    @PostMapping("/in")
    public String gateIn(@RequestParam @NotBlank(message = "预约码不能为空")
                         @Size(max = 24, message = "预约码最长 24 个字符") String code, Model model) {
        var result = gate.gateIn(code, CurrentUser.id());
        model.addAttribute("result", result);
        return page(model);
    }
}
