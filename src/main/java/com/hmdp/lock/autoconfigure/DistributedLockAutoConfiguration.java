package com.hmdp.lock.autoconfigure;

import com.hmdp.lock.client.DistributedLockClient;
import com.hmdp.lock.client.RedissonStyleDistributedLockClient;
import com.hmdp.lock.mapper.DistributedLockMapper;
import com.hmdp.lock.mapper.LockNotifyMapper;
import com.hmdp.lock.mapper.LockSequenceMapper;
import com.hmdp.lock.mapper.LockWaitQueueMapper;
import com.hmdp.lock.task.DistributedLockCleanTask;
import com.hmdp.lock.watchdog.WatchdogManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(DistributedLockClient.class)
@EnableConfigurationProperties(DistributedLockProperties.class)
@MapperScan("${distributed.lock.mapper-location:com.hmdp.lock.mapper}")
public class DistributedLockAutoConfiguration {

    // 注册分布式锁客户端（修复参数问题：添加WatchdogManager参数）
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({DistributedLockMapper.class, LockNotifyMapper.class, LockSequenceMapper.class, LockWaitQueueMapper.class, WatchdogManager.class})
    public DistributedLockClient distributedLockClient() {
        return new RedissonStyleDistributedLockClient();
    }

    // 注册看门狗管理器（从配置中获取参数）
    @Bean
    @ConditionalOnMissingBean
    public WatchdogManager watchdogManager(DistributedLockProperties properties) {
        // 传入看门狗配置
        return new WatchdogManager(properties.getWatchdog());
    }

    // 注册过期锁清理任务
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "distributed.lock.clean", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DistributedLockCleanTask lockCleanTask(
            DistributedLockMapper lockMapper,
            @Autowired(required = false) MeterRegistry meterRegistry) { // 允许监控组件为null
        return new DistributedLockCleanTask(lockMapper, meterRegistry);
    }
}