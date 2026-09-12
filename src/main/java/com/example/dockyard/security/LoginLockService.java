package com.example.dockyard.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败锁定（单机内存实现）：
 *  - 同一用户名连续失败 {@value #MAX_FAILURES} 次，锁定 {@value #LOCK_MINUTES} 分钟；
 *  - 锁定期间 JpaUserDetailsService 直接抛 LockedException，即使密码正确也拒绝；
 *  - 一次成功登录清空计数；锁定期过后自动解锁。
 * 重启进程即清空所有计数（可接受：暴力破解窗口被重置，代价仅为运维重启）。
 */
@Component
public class LoginLockService {

    public static final int MAX_FAILURES = 5;
    public static final int LOCK_MINUTES = 15;

    /** 防止用户名被用作内存放大攻击：超过该容量时顺手清理过期记录 */
    private static final int MAP_SIZE_GUARD = 10_000;

    private static final Logger log = LoggerFactory.getLogger(LoginLockService.class);

    private record Attempt(int failures, Instant lockedUntil) {}

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** 当前是否处于锁定期；锁定期已过则顺手清除并返回 false */
    public boolean isLocked(String username) {
        if (username == null) {
            return false;
        }
        Attempt a = attempts.get(normalize(username));
        if (a == null || a.lockedUntil() == null) {
            return false;
        }
        if (Instant.now().isBefore(a.lockedUntil())) {
            return true;
        }
        attempts.remove(normalize(username), a);
        return false;
    }

    /**
     * 记录一次失败。返回 true 表示本次失败触发了锁定。
     */
    public boolean recordFailure(String username, String clientIp) {
        if (username == null || username.isBlank()) {
            return false;
        }
        evictIfHuge();
        String key = normalize(username);
        boolean[] lockedNow = {false};
        attempts.compute(key, (k, prev) -> {
            int fails = (prev == null ? 0 : prev.failures()) + 1;
            if (fails >= MAX_FAILURES) {
                lockedNow[0] = true;
                log.warn("账号 {} 连续登录失败 {} 次，锁定 {} 分钟，来源 IP {}",
                        key, fails, LOCK_MINUTES, clientIp);
                return new Attempt(fails, Instant.now().plus(Duration.ofMinutes(LOCK_MINUTES)));
            }
            log.info("账号 {} 第 {} 次登录失败，来源 IP {}", key, fails, clientIp);
            return new Attempt(fails, null);
        });
        return lockedNow[0];
    }

    /** 登录成功后清空计数 */
    public void clear(String username) {
        if (username != null) {
            attempts.remove(normalize(username));
        }
    }

    /** 仅供测试：清空全部锁定状态 */
    public void clearAll() {
        attempts.clear();
    }

    private void evictIfHuge() {
        if (attempts.size() < MAP_SIZE_GUARD) {
            return;
        }
        Instant now = Instant.now();
        Iterator<Map.Entry<String, Attempt>> it = attempts.entrySet().iterator();
        while (it.hasNext()) {
            Attempt a = it.next().getValue();
            if (a.lockedUntil() != null && now.isAfter(a.lockedUntil())) {
                it.remove();
            }
        }
    }

    private String normalize(String username) {
        return username.strip();
    }
}
