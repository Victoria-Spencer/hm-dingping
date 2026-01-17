package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private final StringRedisTemplate stringRedisTemplate;

    private final static DefaultRedisScript<Long> UNLOCK_SCRIPT;
    private final static String LOCK_PREFIX = "lock:";

    private final String key;
    private final String value;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("/lua/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock (StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate =  stringRedisTemplate;
        this.key = LOCK_PREFIX + name;
        this.value = UUID.randomUUID().toString(true) + "-" + Thread.currentThread().getId();
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLock);
    }

    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key),
                value
        );
    }
    /*@Override
    public void unLock() {
        String id = stringRedisTemplate.opsForValue().get(key);
        if (value.equals(id)) {
            Boolean isUnLock = stringRedisTemplate.delete(key);
        }
    }*/
}
