package com.example.dockyard.web;

import com.example.dockyard.repo.DockMaintenanceRepository;
import com.example.dockyard.repo.DockRepository;
import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.service.QueueAllocationService;
import com.example.dockyard.service.ReferenceData;
import com.example.dockyard.service.YardClock;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.stream.Collectors;

/** 调度员：等候队列叫号、选择月台派车；另含插单入口（页面在预约模块）。 */
@Controller
@RequestMapping("/dispatch")
public class DispatchController {

    private final QueueAllocationService queue;
    private final DockRepository docks;
    private final DockMaintenanceRepository maintenances;
    private final ReferenceData ref;
    private final YardClock clock;

    public DispatchController(QueueAllocationService queue, DockRepository docks,
                              DockMaintenanceRepository maintenances,
                              ReferenceData ref, YardClock clock) {
        this.queue = queue;
        this.docks = docks;
        this.maintenances = maintenances;
        this.ref = ref;
        this.clock = clock;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("waiting", queue.waitingQueue());
        model.addAttribute("docks", docks.findByActiveTrueOrderByCode());
        // 下拉中禁用当前正处于保养停用窗的月台（服务端派台同样强制拦截）
        Set<Long> blockedDockIds = maintenances.findActiveAt(clock.now()).stream()
                .map(com.example.dockyard.domain.DockMaintenance::getDockId)
                .collect(Collectors.toSet());
        model.addAttribute("blockedDockIds", blockedDockIds);
        model.addAttribute("ref", ref);
        model.addAttribute("clock", clock);
        return "dispatch/page";
    }

    @PostMapping("/call")
    public String call(@RequestParam Long apptId,
                       @RequestParam(required = false) Long dockId) {
        queue.callToDock(apptId, dockId, CurrentUser.id());
        return "redirect:/dispatch";
    }
}
