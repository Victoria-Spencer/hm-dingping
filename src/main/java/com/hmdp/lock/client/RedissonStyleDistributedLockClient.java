package com.hmdp.lock.client;

import cn.hutool.core.lang.UUID;
import com.hmdp.lock.mapper.DistributedLockMapper;
import com.hmdp.lock.mapper.LockNotifyMapper;
import com.hmdp.lock.core.DLock;
import com.hmdp.lock.core.DatabaseDLock;
import com.hmdp.lock.mapper.LockSequenceMapper;
import com.hmdp.lock.watchdog.WatchdogManager;
import org.springframework.stereotype.Component;

// 客户端实现类
@Component
public class RedissonStyleDistributedLockClient implements DistributedLockClient {

    private final DistributedLockMapper lockMapper;
    private final LockNotifyMapper notifyMapper;
    private final LockSequenceMapper sequenceMapper;
    private final WatchdogManager watchdogManager;
    // 生成实例唯一UUID（一台机器中唯一）
    private final String instanceUUID = UUID.randomUUID().toString(true);

    // 构造函数添加LockNotifyMapper参数
    public RedissonStyleDistributedLockClient(DistributedLockMapper lockMapper,
                                              LockNotifyMapper notifyMapper,
                                              LockSequenceMapper sequenceMapper,
                                              WatchdogManager watchdogManager) {
        // 依赖非空校验，提前暴露问题
        if (lockMapper == null) {
            throw new IllegalArgumentException("DistributedLockMapper 不能为空！");
        }
        if (notifyMapper == null) {
            throw new IllegalArgumentException("LockNotifyMapper 不能为空！");
        }
        this.lockMapper = lockMapper;
        this.notifyMapper = notifyMapper;
        this.sequenceMapper = sequenceMapper;
        this.watchdogManager = watchdogManager;
    }

    @Override
    public DLock getLock(String lockKey) {
        // 创建DLock实现类对象，传入所有必要参数
        DatabaseDLock lock = new DatabaseDLock(lockKey, lockMapper, notifyMapper, sequenceMapper, instanceUUID, watchdogManager);
        lock.setSelf(lock); // 手动赋值self，替代Spring注入
        return lock;
    }
}