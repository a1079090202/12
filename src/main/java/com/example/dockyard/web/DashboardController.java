package com.example.dockyard.web;

import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.service.DashboardService;
import com.example.dockyard.service.YardClock;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final DashboardService dashboard;
    private final YardClock clock;

    public DashboardController(DashboardService dashboard, YardClock clock) {
        this.dashboard = dashboard;
        this.clock = clock;
    }

    @GetMapping({"/", "/dashboard"})
    public String dashboard(Model model) {
        var view = dashboard.build(clock.today());
        model.addAttribute("view", view);
        model.addAttribute("me", CurrentUser.get());
        model.addAttribute("clock", clock);
        return "dashboard";
    }
}
