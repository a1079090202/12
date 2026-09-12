package com.example.dockyard.security;

import com.example.dockyard.domain.AppUser;
import com.example.dockyard.domain.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** 登录主体：带上用户 id、角色与承运商 id，供控制器/服务层做数据归属校验 */
public class LoginUser implements UserDetails {

    private final AppUser user;
    private final boolean accountLocked;

    public LoginUser(AppUser user) {
        this(user, false);
    }

    /** accountLocked 由 LoginLockService 的失败计数决定（区别于数据库里的停用标记） */
    public LoginUser(AppUser user, boolean accountLocked) {
        this.user = user;
        this.accountLocked = accountLocked;
    }

    public Long getId() { return user.getId(); }
    public Role getRole() { return user.getRole(); }
    public Long getCarrierId() { return user.getCarrierId(); }
    public String getDisplayName() { return user.getDisplayName(); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() { return user.getPasswordHash(); }

    @Override
    public String getUsername() { return user.getUsername(); }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return !accountLocked; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return user.isActive(); }
}
