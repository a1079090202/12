package com.example.dockyard.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 取当前登录人的便捷方法 */
public final class CurrentUser {

    private CurrentUser() {}

    public static LoginUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser lu) {
            return lu;
        }
        throw new IllegalStateException("当前没有登录用户");
    }

    public static Long id() {
        return get().getId();
    }
}
