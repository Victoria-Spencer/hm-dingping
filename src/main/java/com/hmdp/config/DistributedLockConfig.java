package com.hmdp.config;

import com.hmdp.lock.client.DistributedLockClient;
import com.hmdp.lock.client.RedissonStyleDistributedLockClient;
import com.hmdp.mapper.DistributedLockMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 配置类：初始化客户端并生成实例唯一UUID
@Configuration
public class DistributedLockConfig {

    // 分布式锁客户端（类似RedissonClient）
    @Bean
    public DistributedLockClient distributedLockClient(DistributedLockMapper lockMapper) {
        return new RedissonStyleDistributedLockClient(lockMapper);
    }
}