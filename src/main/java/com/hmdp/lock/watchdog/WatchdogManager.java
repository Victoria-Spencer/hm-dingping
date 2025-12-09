package com.hmdp.lock.watchdog;

import com.hmdp.lock.exception.LockRenewalFailedException;
import com.hmdp.lock.autoconfigure.DistributedLockProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 看门狗管理器：统一管理分布式锁的续期任务线程池（支持配置参数）
 */
@Slf4j
public class WatchdogManager {
    // 线程池
    private final ScheduledExecutorService scheduler;
    // 任务缓存：锁键 -> 续期任务Future
    private final Map<String, ScheduledFuture<?>> renewalTasks = new ConcurrentHashMap<>();
    // 任务缓存锁
    private final ReentrantLock taskLock = new ReentrantLock();

    // 构造器：接收配置参数
    public WatchdogManager(DistributedLockProperties.Watchdog watchdogConfig) {
        // 从配置获取线程池参数（默认值已在配置类中定义）
        int corePoolSize = watchdogConfig.getCorePoolSize();
        String threadNamePrefix = watchdogConfig.getThreadNamePrefix();

        // 初始化线程池
        this.scheduler = new ScheduledThreadPoolExecutor(
                corePoolSize,
                new ThreadFactory() {
                    private final AtomicInteger threadNumber = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, threadNamePrefix + threadNumber.getAndIncrement());
                        thread.setDaemon(true);
                        return thread;
                    }
                },
                new ThreadPoolExecutor.DiscardPolicy() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                        String lockKey = ((RenewalTask) r).getLockKey();
                        log.error("续期任务被拒绝，锁键: {}", lockKey);
                        throw new LockRenewalFailedException("续期任务被拒绝，锁键: " + lockKey);
                    }
                }
        );

        // 注册JVM关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("关闭看门狗线程池...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }));
    }

    /**
     * 提交续期任务
     */
    public void submitRenewalTask(String lockKey, RenewalTask renewalTask,
                                  long initialDelay, long period, TimeUnit unit) {
        taskLock.lock();
        try {
            // 取消已有任务
            cancelRenewalTask(lockKey);
            // 提交新任务
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                    renewalTask, initialDelay, period, unit);
            renewalTasks.put(lockKey, future);
            log.debug("提交续期任务成功，锁键: {}", lockKey);
        } finally {
            taskLock.unlock();
        }
    }

    /**
     * 取消续期任务
     */
    public void cancelRenewalTask(String lockKey) {
        taskLock.lock();
        try {
            ScheduledFuture<?> future = renewalTasks.get(lockKey);
            if (future != null && !future.isCancelled() && !future.isDone()) {
                boolean cancelled = future.cancel(true);
                log.debug("取消续期任务，锁键: {}，取消结果: {}", lockKey, cancelled);
            }
            renewalTasks.remove(lockKey);
        } finally {
            taskLock.unlock();
        }
    }
}