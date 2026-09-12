package com.example.dockyard.web;

import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.security.LoginUser;
import com.example.dockyard.service.DisputeService;
import com.example.dockyard.service.ReferenceData;
import com.example.dockyard.service.YardClock;
import com.example.dockyard.web.form.DisputeAdjustForm;
import com.example.dockyard.web.form.DisputeRaiseForm;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Controller
@Validated
@RequestMapping("/disputes")
public class DisputeController {

    private final DisputeService disputes;
    private final ReferenceData ref;
    private final YardClock clock;

    public DisputeController(DisputeService disputes, ReferenceData ref, YardClock clock) {
        this.disputes = disputes;
        this.ref = ref;
        this.clock = clock;
    }

    /** 调度员：待处理异议列表 */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("open", disputes.openDisputes());
        model.addAttribute("ref", ref);
        model.addAttribute("clock", clock);
        return "dispute/list";
    }

    /** 承运商：对本司某单提异议 */
    @PostMapping("/raise")
    public String raise(@Valid @ModelAttribute DisputeRaiseForm form, BindingResult br) {
        if (br.hasErrors()) {
            throw new com.example.dockyard.service.BusinessRuleException(WebForms.firstError(br));
        }
        LoginUser me = CurrentUser.get();
        disputes.raise(form.getAppointmentId(), form.getReason().strip(),
                me.getCarrierId(), me.getId());
        return "redirect:/appointments/" + form.getAppointmentId();
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id,
                         @RequestParam(required = false)
                         @jakarta.validation.constraints.Size(max = 1000) String note) {
        disputes.reject(id, note, CurrentUser.id());
        return "redirect:/disputes";
    }

    @PostMapping("/{id}/adjust")
    public String adjust(@PathVariable Long id,
                         @Valid @ModelAttribute DisputeAdjustForm form,
                         BindingResult br) {
        if (br.hasErrors()) {
            throw new com.example.dockyard.service.BusinessRuleException(WebForms.firstError(br));
        }
        disputes.adjust(id, form.getAdjustedAmount(), form.getNote(), CurrentUser.id());
        return "redirect:/disputes";
    }
}
