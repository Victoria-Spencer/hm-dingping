package com.hmdp.lock.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.lock.entity.DistributedLock;
import com.hmdp.lock.mapper.DistributedLockMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁过期记录定时清理任务（优化版）
 * 支持可配置清理阈值、批量删除、监控统计
 */
@Component
@Slf4j
public class DistributedLockCleanTask {

    private final DistributedLockMapper lockMapper;
    private final Counter cleanSuccessCounter;
    private final Counter cleanFailCounter;

    // 可配置的过期清理阈值（天），默认7天
    @Value("${distributed.lock.clean.expire-days:7}")
    private int expireDays;

    // 批量删除每次处理的数量，避免大事务
    @Value("${distributed.lock.clean.batch-size:1000}")
    private int batchSize;

    public DistributedLockCleanTask(DistributedLockMapper lockMapper, MeterRegistry meterRegistry) {
        this.lockMapper = lockMapper;
        // 初始化监控指标
        this.cleanSuccessCounter = meterRegistry.counter("distributed.lock.clean.success.count");
        this.cleanFailCounter = meterRegistry.counter("distributed.lock.clean.fail.count");
    }

    /**
     * 定时清理逻辑：可通过配置指定执行时间，默认每天凌晨2点
     */
    @Scheduled(cron = "${distributed.lock.clean.cron:0 0 2 * * ?}")
    public void cleanExpiredLock() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(expireDays);
        log.info("开始执行分布式锁过期清理任务，清理阈值：{}，批量大小：{}", threshold, batchSize);

        try {
            int totalDeleted = 0;
            long startTime = System.currentTimeMillis();

            // 循环批量删除，避免一次性删除过多记录导致事务过长
            while (true) {
                int deleted = batchDeleteExpired(threshold);
                if (deleted == 0) {
                    break;
                }
                totalDeleted += deleted;
                // 每批删除后短暂休眠，避免数据库压力过大
                TimeUnit.MILLISECONDS.sleep(100);
            }

            long costTime = System.currentTimeMillis() - startTime;
            log.info("分布式锁过期清理任务完成，总清理数量：{}，耗时：{}ms，清理阈值：{}",
                    totalDeleted, costTime, threshold);
            cleanSuccessCounter.increment(totalDeleted);
        } catch (Exception e) {
            log.error("分布式锁过期清理任务执行失败，清理阈值：{}", threshold, e);
            cleanFailCounter.increment();
        }
    }

    /**
     * 批量删除过期锁记录（单批）
     */
    @Transactional(rollbackFor = Exception.class)
    public int batchDeleteExpired(LocalDateTime threshold) {
        QueryWrapper<DistributedLock> queryWrapper = new QueryWrapper<DistributedLock>()
                .lt("expire_time", threshold)
                .last("LIMIT " + batchSize); // 限制单批删除数量

        int deleteCount = lockMapper.delete(queryWrapper);
        if (deleteCount > 0) {
            log.debug("批量清理过期锁记录，本次清理数量：{}，清理阈值：{}", deleteCount, threshold);
        }
        return deleteCount;
    }

    /**
     * 手动触发清理（供Controller调用）
     */
    public void manualClean() {
        cleanExpiredLock();
    }
}