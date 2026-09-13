package com.example.dockyard.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

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
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/css/**", "/js/**", "/login", "/error", "/403", "/500").permitAll()
                // 承运商：提交预约、取消、对本司单子提异议
                .requestMatchers("/appointments/new", "/appointments/mine").hasRole("CARRIER")
                .requestMatchers("/appointments/override").hasRole("DISPATCHER")
                .requestMatchers("/appointments/*/dispute").hasRole("CARRIER")
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/appointments", "/appointments/*/cancel").hasAnyRole("CARRIER", "DISPATCHER")
                // 门卫
                .requestMatchers("/gate/**").hasRole("GUARD")
                // 调度员：排队叫号 / 费率配置 / 异议处理 / 月台保养停用
                .requestMatchers("/maintenance", "/maintenance/**").hasRole("DISPATCHER")
                .requestMatchers("/dispatch/**", "/rates/**").hasRole("DISPATCHER")
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/disputes/raise").hasRole("CARRIER")
                .requestMatchers("/disputes", "/disputes/*/reject", "/disputes/*/adjust")
                        .hasRole("DISPATCHER")
                // 仓库
                .requestMatchers("/warehouse/**").hasRole("WAREHOUSE")
                // 核费明细：仅调度（全部）与承运商（本司，控制器内再过滤）；门卫/仓库不可接触金额
                .requestMatchers("/fees/**").hasAnyRole("CARRIER", "DISPATCHER")
                // 首页、详情：登录即可；数据范围在控制器内按角色再卡
                .requestMatchers("/", "/dashboard", "/appointments/*").authenticated()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll())
            // 越权访问统一到 403 页面（@PreAuthorize / URL 规则拦截）
            .exceptionHandling(eh -> eh.accessDeniedPage("/403"))
            .headers(headers -> headers
                // 脚本只允许本站静态资源（模板无内联脚本/事件处理器），样式允许内联 style 属性
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                .frameOptions(fo -> fo.deny())
                .referrerPolicy(rp -> rp.policy(
                        org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                .ReferrerPolicy.SAME_ORIGIN)))
            .csrf(csrf -> {});
        return http.build();
    }
}
