package com.hmdp.lock.factoryBean;

import com.hmdp.lock.core.DatabaseDLock;
import com.hmdp.lock.mapper.DistributedLockMapper;
import com.hmdp.lock.mapper.LockNotifyMapper;
import com.hmdp.lock.mapper.LockSequenceMapper;
import com.hmdp.lock.mapper.LockWaitQueueMapper;
import com.hmdp.lock.watchdog.WatchdogManager;
import jdk.nashorn.internal.objects.annotations.Setter;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

public class DatabaseDLockFactoryBean implements FactoryBean<DatabaseDLock> {
    // 注入DatabaseDLock所需的依赖（均为容器内Bean）
    @Autowired
    private DistributedLockMapper lockMapper;
    @Autowired
    private LockNotifyMapper notifyMapper;
    @Autowired
    private LockSequenceMapper sequenceMapper;
    @Autowired
    private LockWaitQueueMapper waitQueueMapper;
    @Autowired
    private WatchdogManager watchdogManager;
    @Autowired
    private ApplicationContext applicationContext;
    // 动态lockKey，每次创建实例前设置
    private String currentLockKey;

    // 设置当前要创建的lockKey
    @Setter
    public void setCurrentLockKey(String currentLockKey) {
        this.currentLockKey = currentLockKey;
    }

    // 核心：创建DatabaseDLock实例
    @Override
    public DatabaseDLock getObject() {
        DatabaseDLock lock = new DatabaseDLock(
                currentLockKey, lockMapper, notifyMapper, sequenceMapper,
                waitQueueMapper, watchdogManager
        );
        // 1. 先触发Spring自动注入（处理其他@Autowired依赖，如notifyListener）
        applicationContext.getAutowireCapableBeanFactory().autowireBean(lock);
        // 2. 手动绑定self为当前实例
        lock.setSelf(lock);
        return lock;
    }

    @Override
    public Class<?> getObjectType() {
        return DatabaseDLock.class;
    }

    // 确保是单例，避免重复创建
    @Override
    public boolean isSingleton() {
        return true;
    }
}