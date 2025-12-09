package com.hmdp.lock.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "distributed.lock")
@Data
public class DistributedLockProperties {
    // 锁默认配置
    private long defaultLeaseTime = 30000; // 默认租期30秒
    private long renewalInterval = 10000;  // 续期间隔10秒

    // 清理任务配置
    private Clean clean = new Clean();

    @Data
    public static class Clean {
        private int expireDays = 7;         // 过期清理阈值（天）
        private int batchSize = 1000;       // 批量清理大小
        private String cron = "0 0 2 * * ?"; // 清理任务Cron表达式
    }
}