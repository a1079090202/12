package com.example.dockyard.web;

import com.example.dockyard.repo.DockRepository;
import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.service.QueueAllocationService;
import com.example.dockyard.service.ReferenceData;
import com.example.dockyard.service.YardClock;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/** 调度员：等候队列叫号、选择月台派车；另含插单入口（页面在预约模块）。 */
@Controller
@RequestMapping("/dispatch")
public class DispatchController {

    private final QueueAllocationService queue;
    private final DockRepository docks;
    private final ReferenceData ref;
    private final YardClock clock;

    public DispatchController(QueueAllocationService queue, DockRepository docks,
                              ReferenceData ref, YardClock clock) {
        this.queue = queue;
        this.docks = docks;
        this.ref = ref;
        this.clock = clock;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("waiting", queue.waitingQueue());
        model.addAttribute("docks", docks.findByActiveTrueOrderByCode());
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
