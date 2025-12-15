package com.hmdp.config;

import org.redisson.RedissonRedLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 红锁工厂类，用于动态创建RedissonRedLock实例
 */
@Component
public class RedLockFactory {
    private final List<RedissonClient> redissonClients;

    public RedLockFactory(List<RedissonClient> redissonClients) {
        this.redissonClients = redissonClients;
    }

    /**
     * 提供创建红锁的工厂方法（非Bean，根据锁键动态生成）
     * 调用方式：redLockFactory.createRedLock("lockKey")
     */
    public RedissonRedLock createRedLock(String lockKey) {
        RLock[] locks = redissonClients.stream()
                .map(client -> client.getLock(lockKey)) // 所有节点使用相同锁键
                .toArray(RLock[]::new);
        return new RedissonRedLock(locks);
    }
}