package com.hmdp.lock.core;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.lock.entity.DistributedLock;
import com.hmdp.lock.exception.LockAcquireFailedException;
import com.hmdp.lock.mapper.DistributedLockMapper;
import com.hmdp.lock.watchdog.RenewalTask;
import com.hmdp.lock.watchdog.WatchdogManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

/**
 * 模仿Redisson风格实现的数据库分布式锁（实现DLock接口）
 */
@Slf4j
public class DatabaseDLock implements DLock {

    private final String lockKey;
    private final DistributedLockMapper lockMapper;
    private final String instanceUUID;
    private final ThreadLocal<String> holderThreadLocal = new ThreadLocal<>();
    // 看门狗管理器（通过构造函数注入）
    private final WatchdogManager watchdogManager;
    // 默认过期时间30秒（看门狗机制）
    private static final long DEFAULT_LEASE_TIME = 30 * 1000;
    // 重试间隔（毫秒）
    private static final long RETRY_INTERVAL_MS = 100;
    // 存储当前锁的租期（毫秒）
    private long leaseTime;
    // 是否启用看门狗
    private boolean useWatchDog;

    // 自我注入解决事务自调用问题
    @Autowired
    @Lazy
    private DatabaseDLock self;

    // 构造函数添加WatchdogManager参数
    public DatabaseDLock(String lockKey, DistributedLockMapper lockMapper, String instanceUUID, WatchdogManager watchdogManager) {
        this.lockKey = lockKey;
        this.lockMapper = lockMapper;
        this.instanceUUID = instanceUUID;
        this.watchdogManager = watchdogManager;
    }

    /**
     * 手动设置self引用
     */
    public void setSelf(DatabaseDLock self) {
        this.self = self;
    }

    /**
     * 生成持有者标识（实例UUID + 线程ID）
     */
    private String getHolder() {
        String holder = holderThreadLocal.get();
        if (holder == null) {
            long threadId = Thread.currentThread().getId();
            holder = instanceUUID + ":" + threadId;
            holderThreadLocal.set(holder);
        }
        return holder;
    }

    /**
     * Redisson风格：无参获取锁（阻塞直到获取）
     */
    @Override
    public void lock() {
        try {
            lock(DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquireFailedException("获取锁被中断", e);
        }
    }

    /**
     * Redisson风格：带租期的锁（阻塞直到获取）
     */
    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        this.leaseTime = unit.toMillis(leaseTime);
        this.useWatchDog = leaseTime == -1; // -1表示使用看门狗

        if (!tryLock(Long.MAX_VALUE, useWatchDog ? DEFAULT_LEASE_TIME : this.leaseTime, TimeUnit.MILLISECONDS)) {
            throw new LockAcquireFailedException("获取锁超时");
        }
    }

    /**
     * Redisson风格：尝试获取锁（无等待时间）
     */
    @Override
    public boolean tryLock() {
        try {
            return tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Redisson核心API：带等待时间和租期的尝试获取锁
     */
    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        long waitMillis = unit.toMillis(waitTime);
        this.leaseTime = unit.toMillis(leaseTime);
        this.useWatchDog = leaseTime == -1;

        long start = System.currentTimeMillis();
        long remaining;

        // 循环重试直到超时
        while (true) {
            // 通过自我代理调用事务方法，确保事务生效
            Boolean acquired = self.tryAcquire(useWatchDog ? DEFAULT_LEASE_TIME : this.leaseTime);
            if (acquired != null && acquired) {
                return true;
            }

            // 计算剩余等待时间
            remaining = waitMillis - (System.currentTimeMillis() - start);
            if (remaining <= 0) {
                return false;
            }

            // 等待重试
            TimeUnit.MILLISECONDS.sleep(Math.min(RETRY_INTERVAL_MS, remaining));
        }
    }

    /**
     * 内部获取锁逻辑（模仿Redisson的tryAcquire）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Boolean tryAcquire(long leaseMillis) {
        String holder = getHolder();
        LocalDateTime expireTime = LocalDateTime.now().plus(leaseMillis, ChronoUnit.MILLIS);

        try {
            // 行锁抢占
            DistributedLock lock = lockMapper.getLockWithExLock(lockKey);

            if (lock == null) {
                // 锁不存在，创建新锁
                createNewLock(holder, expireTime);
                startRenewalIfNeeded(leaseMillis);
                return true;
            } else if (isLockExpired(lock)) {
                // 锁已过期，尝试抢占
                boolean acquired = tryAcquireExpiredLock(holder, expireTime);
                if (acquired) {
                    startRenewalIfNeeded(leaseMillis);
                }
                return acquired;
            } else if (isCurrentHolder(lock, holder)) {
                // 重入锁处理
                return incrementReentrantCount(holder, expireTime);
            }

            // 锁被其他持有者占用
            return false;
        } catch (Exception e) {
            log.error("获取锁异常: {}", lockKey, e);
            return null;
        }
    }

    /**
     * 释放锁（模仿Redisson的unlock逻辑）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlock() {
        String holder = getHolder();
        try {
            // 停止续期任务
            stopRenewalTask();

            DistributedLock lock = lockMapper.getLockWithExLock(lockKey);
            if (lock == null) {
                log.warn("锁已不存在: {}", lockKey);
                return;
            }

            if (!isCurrentHolder(lock, holder)) {
                throw new IllegalMonitorStateException("释放非当前持有者的锁: " + lockKey);
            }

            // 处理重入计数
            int newCount = lock.getReentrantCount() - 1;
            if (newCount > 0) {
                updateReentrantCount(holder, newCount);
            } else {
                // 彻底删除锁
                lockMapper.delete(new QueryWrapper<DistributedLock>()
                        .eq("lock_key", lockKey)
                        .eq("holder", holder));
                holderThreadLocal.remove();
            }
            log.debug("释放锁成功: {}", lockKey);
        } catch (Exception e) {
            log.error("释放锁异常: {}", lockKey, e);
            throw new RuntimeException("释放锁失败", e);
        }
    }

    // 辅助方法：创建新锁
    private void createNewLock(String holder, LocalDateTime expireTime) {
        DistributedLock lock = new DistributedLock();
        lock.setLockKey(lockKey);
        lock.setHolder(holder);
        lock.setExpireTime(expireTime);
        lock.setReentrantCount(1);
        lockMapper.insert(lock);
        log.debug("创建新锁: {}，过期时间: {}", lockKey, expireTime);
    }

    // 辅助方法：尝试抢占过期锁
    private boolean tryAcquireExpiredLock(String holder, LocalDateTime expireTime) {
        int updateCount = lockMapper.update(
                new DistributedLock() {{
                    setHolder(holder);
                    setExpireTime(expireTime);
                    setReentrantCount(1);
                }},
                new QueryWrapper<DistributedLock>()
                        .eq("lock_key", lockKey)
                        .le("expire_time", LocalDateTime.now())
        );
        return updateCount > 0;
    }

    // 辅助方法：重入计数+1
    private boolean incrementReentrantCount(String holder, LocalDateTime expireTime) {
        int updateCount = lockMapper.incrementReentrantCount(lockKey, holder, expireTime);
        if (updateCount > 0) {
            log.debug("锁重入: {}，当前计数: {}", lockKey, updateCount);
        }
        return updateCount > 0;
    }

    // 辅助方法：更新重入计数
    private void updateReentrantCount(String holder, int newCount) {
        lockMapper.decrementReentrantCount(lockKey, holder,
                LocalDateTime.now().plus(leaseTime, ChronoUnit.MILLIS));
        log.debug("锁重入计数更新: {}，新计数: {}", lockKey, newCount);
    }

    /**
     * 启动续期任务（仅当使用看门狗时）
     */
    private void startRenewalIfNeeded(long leaseMillis) {
        if (useWatchDog) {
            // 使用传入的实际租期计算续期间隔
            long renewalInterval = leaseMillis / 3;
            startRenewalTask(renewalInterval);
        }
    }

    /**
     * 使用看门狗管理器启动续期任务
     */
    private void startRenewalTask(long intervalMillis) {
        // 创建续期任务
        RenewalTask renewalTask = new RenewalTask() {
            @Override
            public String getLockKey() {
                return lockKey;
            }

            @Override
            public void run() {
                try {
                    LocalDateTime newExpire = LocalDateTime.now().plus(leaseTime, ChronoUnit.MILLIS);
                    int updateCount = lockMapper.extendLockExpire(lockKey, getHolder(), newExpire);

                    if (updateCount == 0) {
                        log.warn("锁续期失败，可能已被释放: {}", lockKey);
                        watchdogManager.cancelRenewalTask(lockKey);
                    } else {
                        log.trace("锁续期成功: {}，新过期时间: {}", lockKey, newExpire);
                    }
                } catch (Exception e) {
                    log.error("锁续期任务异常: {}", lockKey, e);
                    watchdogManager.cancelRenewalTask(lockKey);
                }
            }
        };

        // 提交任务到看门狗管理器
        watchdogManager.submitRenewalTask(
                lockKey,
                renewalTask,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
        log.debug("通过看门狗启动续期任务: {}，间隔: {}ms", lockKey, intervalMillis);
    }

    /**
     * 停止续期任务
     */
    private void stopRenewalTask() {
        watchdogManager.cancelRenewalTask(lockKey);
    }

    // 辅助方法：判断锁是否过期
    private boolean isLockExpired(DistributedLock lock) {
        return lock.getExpireTime().isBefore(LocalDateTime.now());
    }

    // 辅助方法：判断是否为当前持有者
    private boolean isCurrentHolder(DistributedLock lock, String holder) {
        return holder.equals(lock.getHolder());
    }

    // 暂不支持条件锁
    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException("不支持条件锁");
    }
}