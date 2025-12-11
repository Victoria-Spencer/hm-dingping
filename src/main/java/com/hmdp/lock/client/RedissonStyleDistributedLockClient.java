package com.hmdp.lock.client;

import cn.hutool.core.lang.UUID;
import com.hmdp.lock.mapper.DistributedLockMapper;
import com.hmdp.lock.mapper.LockNotifyMapper;
import com.hmdp.lock.core.DLock;
import com.hmdp.lock.core.DatabaseDLock;
import com.hmdp.lock.mapper.LockSequenceMapper;
import com.hmdp.lock.mapper.LockWaitQueueMapper;
import com.hmdp.lock.watchdog.WatchdogManager;
import org.springframework.stereotype.Component;

// 客户端实现类
@Component
public class RedissonStyleDistributedLockClient implements DistributedLockClient {

    private final DistributedLockMapper lockMapper;
    private final LockNotifyMapper notifyMapper;
    private final LockSequenceMapper sequenceMapper;
    private final WatchdogManager watchdogManager;
    private final LockWaitQueueMapper waitQueueMapper;
    // 生成实例唯一UUID（一台机器中唯一）
    private final String instanceUUID = UUID.randomUUID().toString(true);

    public RedissonStyleDistributedLockClient(DistributedLockMapper lockMapper,
                                              LockNotifyMapper notifyMapper,
                                              LockSequenceMapper sequenceMapper,
                                              LockWaitQueueMapper waitQueueMapper,
                                              WatchdogManager watchdogManager) {
        // 补充sequenceMapper校验
        if (sequenceMapper == null) {
            throw new IllegalArgumentException("LockSequenceMapper 不能为空！");
        }
        if (lockMapper == null) {
            throw new IllegalArgumentException("DistributedLockMapper 不能为空！");
        }
        if (notifyMapper == null) {
            throw new IllegalArgumentException("LockNotifyMapper 不能为空！");
        }
        if (waitQueueMapper == null) {
            throw new IllegalArgumentException("LockWaitQueueMapper 不能为空！");
        }
        this.lockMapper = lockMapper;
        this.notifyMapper = notifyMapper;
        this.sequenceMapper = sequenceMapper;
        this.waitQueueMapper = waitQueueMapper;
        this.watchdogManager = watchdogManager;
    }

    @Override
    public DLock getLock(String lockKey) {
        DatabaseDLock lock = new DatabaseDLock(
                lockKey,
                lockMapper,
                notifyMapper,
                sequenceMapper,
                waitQueueMapper,
                instanceUUID,
                watchdogManager);
        lock.setSelf(lock);
        return lock;
    }
}