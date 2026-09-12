package com.example.dockyard.security;

import com.example.dockyard.domain.AppUser;
import com.example.dockyard.repo.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final LoginLockService lockService;

    public JpaUserDetailsService(AppUserRepository users, LoginLockService lockService) {
        this.users = users;
        this.lockService = lockService;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String name = username.strip();
        AppUser user = users.findByUsername(name)
                .orElseThrow(() -> new UsernameNotFoundException("账号不存在：" + username));
        // 锁定状态挂在 UserDetails 上，由 DaoAuthenticationProvider 的 pre-auth 检查
        // 抛原生 LockedException（在 loadUserByUsername 内直接抛会被包成内部服务异常）
        return new LoginUser(user, lockService.isLocked(name));
    }
}
