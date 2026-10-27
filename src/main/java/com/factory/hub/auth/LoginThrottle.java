package com.factory.hub.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流：同一 IP 连续失败 5 次，锁 5 分钟。
 * <p>
 * 内网也挡。车间的服务器常常整段网络都能访问，密码又是「车间名字+123」这种，
 * 没有限流的话一个脚本半小时能试完全部组合。
 * <p>
 * 用内存不用库：重启就清零，对单机场景够用，也避免了"锁表被写爆"。
 * 反代后面拿不到真实 IP 时（所有请求都是网关 IP），这个限流会误伤——
 * 那种情况应该在网关上做，README 里写了。
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 5 * 60 * 1000L;

    private static final class State {
        int fails;
        long lockUntil;
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** 还要锁多少秒，0 表示没锁 */
    public long lockedSeconds(String ip) {
        State s = states.get(ip);
        if (s == null) {
            return 0;
        }
        long left = s.lockUntil - System.currentTimeMillis();
        if (left <= 0 && s.fails >= MAX_FAILS) {
            states.remove(ip);              // 锁到期，重新开始计数
            return 0;
        }
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    public void fail(String ip) {
        State s = states.computeIfAbsent(ip, k -> new State());
        s.fails++;
        if (s.fails >= MAX_FAILS) {
            s.lockUntil = System.currentTimeMillis() + LOCK_MS;
            log.warn("登录失败 {} 次，锁定 IP {} 5 分钟", s.fails, ip);
        }
    }

    public void success(String ip) {
        states.remove(ip);
    }
}
