package com.hmdp.lock.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import javax.validation.constraints.Min;

@Data
@ConfigurationProperties(prefix = "distributed.lock")
@Validated // 启用配置校验
public class DistributedLockProperties {

    private String mapperLocation = "com.hmdp.lock.mapper"; // 默认路径

    // 锁默认配置
    @Min(value = 1000, message = "默认租期不能小于1秒")
    private long defaultLeaseTime = 30000; // 默认租期30秒

    @Min(value = 500, message = "续期间隔不能小于500毫秒")
    private long renewalInterval = 10000;  // 续期间隔10秒

    // 看门狗配置
    private Watchdog watchdog = new Watchdog();

    // 清理任务配置
    private Clean clean = new Clean();

    @Data
    public static class Watchdog {
        @Min(value = 1, message = "核心线程数至少为1")
        private int corePoolSize = 2; // 默认核心线程数

        private String threadNamePrefix = "lock-watchdog-"; // 线程名前缀
    }

    @Data
    public static class Clean {
        @Min(value = 1, message = "过期清理阈值至少为1天")
        private int expireDays = 7;         // 过期清理阈值（天）

        @Min(value = 100, message = "批量清理大小至少为100")
        private int batchSize = 1000;       // 批量清理大小

        private String cron = "0 0 2 * * ?"; // 清理任务Cron表达式
    }
}