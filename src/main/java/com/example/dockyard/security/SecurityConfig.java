package com.example.dockyard.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

import java.io.IOException;

/**
 * 服务端鉴权：URL 规则 + 服务方法 @PreAuthorize 双重强制。
 * 页面按钮显隐只是体验；直接访问 URL 或绕过页面调接口照样被拦（返回 403）。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, LoginLockService lockService) throws Exception {
        http
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/css/**", "/js/**", "/login", "/error").permitAll()
                // 承运商：提交预约、取消、对本司单子提异议
                .requestMatchers("/appointments/new", "/appointments/mine").hasRole("CARRIER")
                .requestMatchers("/appointments/override").hasRole("DISPATCHER")
                .requestMatchers("/appointments/*/dispute").hasRole("CARRIER")
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/appointments", "/appointments/*/cancel").hasAnyRole("CARRIER", "DISPATCHER")
                // 门卫
                .requestMatchers("/gate/**").hasRole("GUARD")
                // 调度员：排队叫号 / 异议处理 / 费率维护
                .requestMatchers("/dispatch/**").hasRole("DISPATCHER")
                .requestMatchers("/rates/**").hasRole("DISPATCHER")
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/disputes/raise").hasRole("CARRIER")
                .requestMatchers("/disputes", "/disputes/*/reject", "/disputes/*/adjust")
                        .hasRole("DISPATCHER")
                // 仓库
                .requestMatchers("/warehouse/**").hasRole("WAREHOUSE")
                // 首页、详情、下载：登录即可；数据范围在控制器内按角色再卡
                .requestMatchers("/", "/dashboard", "/appointments/*", "/fees/**").authenticated()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(authenticationSuccessHandler(lockService))
                .failureHandler(authenticationFailureHandler(lockService))
                .permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll())
            .exceptionHandling(eh -> eh.accessDeniedPage("/error"))
            .headers(headers -> headers
                // 内联脚本已全部外置；页面仍有内联 style 属性，保留 style 'unsafe-inline'
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' data:; base-uri 'self'; form-action 'self'; "
                                + "frame-ancestors 'none'"))
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(ref -> ref.policy(
                        org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                // HSTS 保持 Spring Security 默认开启（仅在 HTTPS 响应上出现该头）
            )
            .csrf(csrf -> {});
        return http.build();
    }

    /** 登录成功：清空失败计数，再跳首页 */
    private AuthenticationSuccessHandler authenticationSuccessHandler(LoginLockService lockService) {
        SimpleUrlAuthenticationSuccessHandler handler = new SimpleUrlAuthenticationSuccessHandler("/") {
            @Override
            public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                                Authentication authentication)
                    throws IOException, jakarta.servlet.ServletException {
                lockService.clear(authentication.getName());
                super.onAuthenticationSuccess(request, response, authentication);
            }
        };
        handler.setAlwaysUseDefaultTargetUrl(true);
        return handler;
    }

    /** 登录失败：计数 +1；本次触发锁定或账号已在锁定期，跳 ?locked，否则跳 ?error */
    private AuthenticationFailureHandler authenticationFailureHandler(LoginLockService lockService) {
        return (request, response, exception) -> {
            String username = request.getParameter("username");
            String ip = request.getRemoteAddr();
            String target;
            if (exception instanceof org.springframework.security.authentication.LockedException
                    || lockService.recordFailure(username, ip)) {
                target = "/login?locked";
            } else {
                target = "/login?error";
            }
            response.sendRedirect(request.getContextPath() + target);
        };
    }
}
