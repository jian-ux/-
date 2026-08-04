package com.feisheng.bot.common.util;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class RedisUtil {
    private final StringRedisTemplate template;

    public RedisUtil(StringRedisTemplate template) {
        this.template = template;
    }

    public void set(String k, Object v) { template.opsForValue().set(k, String.valueOf(v)); }
    public void setex(String k, Object v, long t, TimeUnit u) {
        template.opsForValue().set(k, String.valueOf(v), t, u);
    }
    public String get(String k) { return template.opsForValue().get(k); }
    public Boolean del(String k) { return template.delete(k); }
    public Boolean setnx(String k, Object v, long t, TimeUnit u) {
        Boolean r = template.opsForValue().setIfAbsent(k, String.valueOf(v), t, u);
        return r != null && r;
    }
}
