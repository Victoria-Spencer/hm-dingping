package com.hmdp.lock.client;

import com.hmdp.lock.core.DLock;

// 分布式锁客户端（模仿RedissonClient）
public interface DistributedLockClient {
    /**
     * 获取锁对象
     * @param lockKey 锁标识
     * @return 锁对象（DLock类型）
     */
    DLock getLock(String lockKey);
}