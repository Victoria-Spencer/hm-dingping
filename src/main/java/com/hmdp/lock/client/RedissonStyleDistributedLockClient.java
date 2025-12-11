package com.hmdp.lock.client;

import com.hmdp.lock.factoryBean.DatabaseDLockFactoryBean;
import com.hmdp.lock.core.DLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

// 客户端实现类
@Component
public class RedissonStyleDistributedLockClient implements DistributedLockClient {

    @Autowired
    private ApplicationContext applicationContext;

    public RedissonStyleDistributedLockClient() {}

    @Override
    public DLock getLock(String lockKey) {
        // 1. 获取FactoryBean实例
        DatabaseDLockFactoryBean factoryBean = applicationContext.getBean(DatabaseDLockFactoryBean.class);
        // 2. 设置动态lockKey
        factoryBean.setCurrentLockKey(lockKey);
        // 3. 创建DatabaseDLock实例（自动注入依赖）
        return factoryBean.getObject();
    }
}