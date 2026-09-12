package com.example.dockyard.web;

import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.security.LoginUser;
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
        LoginUser me = CurrentUser.get();
        var view = dashboard.build(clock.today(), me.getRole(), me.getCarrierId());
        model.addAttribute("view", view);
        model.addAttribute("me", me);
        model.addAttribute("clock", clock);
        return "dashboard";
    }
}
