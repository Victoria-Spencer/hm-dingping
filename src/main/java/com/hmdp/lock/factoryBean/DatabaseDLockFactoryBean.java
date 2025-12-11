package com.hmdp.lock.factoryBean;

import com.hmdp.lock.core.DatabaseDLock;
import com.hmdp.lock.mapper.DistributedLockMapper;
import com.hmdp.lock.mapper.LockNotifyMapper;
import com.hmdp.lock.mapper.LockSequenceMapper;
import com.hmdp.lock.mapper.LockWaitQueueMapper;
import com.hmdp.lock.watchdog.WatchdogManager;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
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
    @Value("${lock.instance-uuid}")
    private String instanceUUID;

    @Autowired
    private ApplicationContext applicationContext;

    // 动态lockKey，每次创建实例前设置
    private String currentLockKey;

    // 设置当前要创建的lockKey
    public void setCurrentLockKey(String currentLockKey) {
        this.currentLockKey = currentLockKey;
    }

    // 核心：创建DatabaseDLock实例
    @Override
    public DatabaseDLock getObject() {
        DatabaseDLock lock = new DatabaseDLock(
                currentLockKey, lockMapper, notifyMapper, sequenceMapper,
                waitQueueMapper, instanceUUID, watchdogManager
        );
        // 让Spring为手动创建的实例注入@Autowired依赖（如notifyListener、self）
        applicationContext.getAutowireCapableBeanFactory().autowireBean(lock);
        return lock;
    }

    @Override
    public Class<?> getObjectType() {
        return DatabaseDLock.class;
    }

    // 非单例，每次创建新实例
    @Override
    public boolean isSingleton() {
        return false;
    }
}