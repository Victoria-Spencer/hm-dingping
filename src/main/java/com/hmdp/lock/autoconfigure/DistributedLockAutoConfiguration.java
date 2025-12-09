package com.hmdp.lock.autoconfigure;

import com.hmdp.lock.client.DistributedLockClient;
import com.hmdp.lock.client.RedissonStyleDistributedLockClient;
import com.hmdp.lock.mapper.DistributedLockMapper;
import com.hmdp.lock.task.DistributedLockCleanTask;
import com.hmdp.lock.watchdog.WatchdogManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(DistributedLockClient.class) // 存在客户端类时生效
@EnableConfigurationProperties(DistributedLockProperties.class) // 启用配置绑定
@MapperScan("com.hmdp.lock.mapper") // 扫描Starter中的Mapper接口
public class DistributedLockAutoConfiguration {

    // 注册分布式锁客户端
    @Bean
    @ConditionalOnMissingBean // 允许用户自定义客户端
    public DistributedLockClient distributedLockClient(
            DistributedLockMapper lockMapper,
            DistributedLockProperties properties) {
        return new RedissonStyleDistributedLockClient(lockMapper);
    }

    // 注册看门狗管理器（单例）
    @Bean
    @ConditionalOnMissingBean
    public WatchdogManager watchdogManager() {
        return WatchdogManager.getInstance();
    }

    // 注册过期锁清理任务
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "distributed.lock.clean", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DistributedLockCleanTask lockCleanTask(
            DistributedLockMapper lockMapper,
            DistributedLockProperties properties,
            MeterRegistry meterRegistry) {
        return new DistributedLockCleanTask(lockMapper, meterRegistry);
    }
}