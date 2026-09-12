package com.example.dockyard.web;

import com.example.dockyard.domain.AppUser;
import com.example.dockyard.repo.AppUserRepository;
import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.security.LoginUser;
import com.example.dockyard.web.form.PasswordChangeForm;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 账号自助：修改本人密码（校验旧密码，强制新密码最小长度） */
@Controller
@RequestMapping("/account")
public class AccountController {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;

    public AccountController(AppUserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @GetMapping("/password")
    public String page(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new PasswordChangeForm());
        }
        return "account/password";
    }

    @PostMapping("/password")
    public String change(@Valid @ModelAttribute("form") PasswordChangeForm form, BindingResult br) {
        LoginUser me = CurrentUser.get();
        AppUser user = users.findById(me.getId()).orElseThrow();
        if (!encoder.matches(form.getOldPassword(), user.getPasswordHash())) {
            br.rejectValue("oldPassword", "mismatch", "当前密码不正确");
        }
        if (form.getNewPassword() != null && !form.getNewPassword().equals(form.getConfirmPassword())) {
            br.rejectValue("confirmPassword", "diff", "两次输入的新密码不一致");
        }
        if (form.getNewPassword() != null && form.getNewPassword().equals(form.getOldPassword())) {
            br.rejectValue("newPassword", "same", "新密码不能与当前密码相同");
        }
        if (br.hasErrors()) {
            return "account/password";
        }
        user.setPasswordHash(encoder.encode(form.getNewPassword()));
        users.save(user);
        return "redirect:/account/password?changed=1";
    }
}
