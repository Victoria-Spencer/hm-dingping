package com.hmdp.lock.core;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.lock.entity.DistributedLock;
import com.hmdp.lock.entity.LockNotify;
import com.hmdp.lock.exception.LockAcquireFailedException;
import com.hmdp.lock.listener.LockNotifyListener;
import com.hmdp.lock.mapper.DistributedLockMapper;
import com.hmdp.lock.mapper.LockNotifyMapper;
import com.hmdp.lock.mapper.LockSequenceMapper;
import com.hmdp.lock.watchdog.RenewalTask;
import com.hmdp.lock.watchdog.WatchdogManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

@Slf4j
public class DatabaseDLock implements DLock {

    private final String lockKey;
    private final DistributedLockMapper lockMapper;
    private final LockNotifyMapper notifyMapper;
    private final LockSequenceMapper sequenceMapper;
    private final String instanceUUID;
    private final ThreadLocal<String> holderThreadLocal = new ThreadLocal<>();
    private final ThreadLocal<Integer> reentrantCountThreadLocal = new ThreadLocal<>();
    private final WatchdogManager watchdogManager;
    private static final long DEFAULT_LEASE_TIME = 30 * 1000;
    private static final long NOTIFY_CHECK_INTERVAL_MS = 50;

    private long leaseTime;
    private boolean useWatchDog;

    @Autowired
    @Lazy
    private DatabaseDLock self;
    @Autowired
    private LockNotifyListener notifyListener;

    public DatabaseDLock(String lockKey, DistributedLockMapper lockMapper,
                         LockNotifyMapper notifyMapper,
                         LockSequenceMapper sequenceMapper,
                         String instanceUUID,
                         WatchdogManager watchdogManager) {
        this.lockKey = lockKey;
        this.lockMapper = lockMapper;
        this.notifyMapper = notifyMapper;
        this.sequenceMapper = sequenceMapper;
        this.instanceUUID = instanceUUID;
        this.watchdogManager = watchdogManager;
    }

    public void setSelf(DatabaseDLock self) {
        this.self = self;
    }

    private String getHolder() {
        String holder = holderThreadLocal.get();
        if (holder == null) {
            long threadId = Thread.currentThread().getId();
            holder = instanceUUID + ":" + threadId; // 线程唯一标识
            holderThreadLocal.set(holder);
        }
        return holder;
    }

    private int getReentrantCount() {
        Integer count = reentrantCountThreadLocal.get();
        return count != null ? count : 0;
    }

    private void setReentrantCount(int count) {
        if (count <= 0) {
            reentrantCountThreadLocal.remove();
            holderThreadLocal.remove();
        } else {
            reentrantCountThreadLocal.set(count);
        }
    }

    @Override
    public void lock() {
        try {
            lock(DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquireFailedException("获取锁被中断", e);
        }
    }

    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        this.leaseTime = unit.toMillis(leaseTime);
        this.useWatchDog = leaseTime == -1;

        if (!tryLock(Long.MAX_VALUE, useWatchDog ? DEFAULT_LEASE_TIME : this.leaseTime, TimeUnit.MILLISECONDS)) {
            throw new LockAcquireFailedException("获取锁超时");
        }
    }

    @Override
    public boolean tryLock() {
        try {
            return tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        long waitMillis = unit.toMillis(waitTime);
        this.leaseTime = unit.toMillis(leaseTime);
        this.useWatchDog = leaseTime == -1;

        long start = System.currentTimeMillis();
        long remaining;

        while (true) {
            Boolean acquired = self.tryAcquire(useWatchDog ? DEFAULT_LEASE_TIME : this.leaseTime);
            if (acquired != null && acquired) {
                notifyMapper.deleteByLockKey(lockKey);
                return true;
            }

            remaining = waitMillis - (System.currentTimeMillis() - start);
            if (remaining <= 0) {
                return false;
            }

            // 订阅时传入当前线程标识
            boolean notified = subscribeAndWait(remaining, getHolder());
            if (!notified) {
                return false;
            }
        }
    }

    // 订阅方法：传入线程标识
    private boolean subscribeAndWait(long timeout, String threadId) throws InterruptedException {
        sequenceMapper.initSequenceIfAbsent(lockKey);
        long expectedSeq = sequenceMapper.incrementAndGet(lockKey);

        // 传入线程标识订阅
        CountDownLatch latch = notifyListener.subscribe(lockKey, threadId);
        try {
            boolean notified = latch.await(timeout, TimeUnit.MILLISECONDS);
            if (notified) {
                // 检查是否是有效通知
                List<LockNotify> notifications = notifyMapper.selectByLockKeyAndSequence(lockKey, expectedSeq);
                return !notifications.isEmpty();
            }
            return false;
        } finally {
            // 取消订阅时传入线程标识
            notifyListener.unsubscribe(lockKey, threadId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Boolean tryAcquire(long leaseMillis) {
        String holder = getHolder();
        LocalDateTime expireTime = LocalDateTime.now().plus(leaseMillis, ChronoUnit.MILLIS);

        try {
            DistributedLock lock = lockMapper.getLockWithExLock(lockKey);

            if (lock == null) {
                DistributedLock newLock = new DistributedLock();
                newLock.setLockKey(lockKey);
                newLock.setHolder(holder);
                newLock.setExpireTime(expireTime);
                newLock.setReentrantCount(1);
                lockMapper.insert(newLock);
                setReentrantCount(1);
                startRenewalIfNeeded(leaseMillis);
                log.debug("创建新锁: {}，过期时间: {}", lockKey, expireTime);
                return true;
            } else if (isLockExpired(lock)) {
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
                boolean acquired = updateCount > 0;
                if (acquired) {
                    setReentrantCount(1);
                    startRenewalIfNeeded(leaseMillis);
                }
                return acquired;
            } else if (isCurrentHolder(lock, holder)) {
                int updateCount = lockMapper.incrementReentrantCount(lockKey, holder, expireTime);
                if (updateCount > 0) {
                    int newCount = getReentrantCount() + 1;
                    setReentrantCount(newCount);
                    log.debug("锁重入: {}，当前计数: {}", lockKey, newCount);
                }
                return updateCount > 0;
            }

            return false;
        } catch (Exception e) {
            log.error("获取锁异常: {}", lockKey, e);
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlock() {
        String holder = holderThreadLocal.get();
        if (holder == null) {
            throw new IllegalMonitorStateException("未持有锁，无法释放: " + lockKey);
        }

        try {
            int currentCount = getReentrantCount();
            if (currentCount <= 0) {
                holderThreadLocal.remove();
                reentrantCountThreadLocal.remove();
                throw new IllegalMonitorStateException("锁重入计数异常: " + lockKey);
            }

            int newCount = currentCount - 1;
            setReentrantCount(newCount);

            DistributedLock lock = lockMapper.getLockWithExLock(lockKey);
            if (lock == null) {
                log.warn("锁已不存在: {}", lockKey);
                return;
            }

            if (!isCurrentHolder(lock, holder)) {
                throw new IllegalMonitorStateException("释放非当前持有者的锁: " + lockKey);
            }

            if (newCount > 0) {
                LocalDateTime newExpire = LocalDateTime.now().plus(leaseTime, ChronoUnit.MILLIS);
                lockMapper.decrementReentrantCount(lockKey, holder, newExpire);
                log.debug("锁重入计数更新: {}，新计数: {}", lockKey, newCount);
            } else {
                // 删除锁记录
                lockMapper.delete(new QueryWrapper<DistributedLock>()
                        .eq("lock_key", lockKey)
                        .eq("holder", holder));

                // 生成通知（无需目标线程ID，唤醒所有订阅者）
                sequenceMapper.initSequenceIfAbsent(lockKey);
                Long sequence = sequenceMapper.incrementAndGet(lockKey);
                LockNotify notify = new LockNotify();
                notify.setLockKey(lockKey);
                notify.setSequence(sequence);
                notify.setNotifyTime(LocalDateTime.now());
                notifyMapper.insert(notify);
                log.debug("插入锁释放通知: {}，序列号: {}", lockKey, sequence);

                stopRenewalTask();
            }
            log.debug("释放锁成功: {}，当前计数: {}", lockKey, newCount);
        } catch (Exception e) {
            log.error("释放锁异常: {}", lockKey, e);
            throw new RuntimeException("释放锁失败", e);
        }
    }

    private void startRenewalIfNeeded(long leaseMillis) {
        if (useWatchDog) {
            long renewalInterval = leaseMillis / 3;
            startRenewalTask(renewalInterval);
        }
    }

    private void startRenewalTask(long intervalMillis) {
        String originalHolder = getHolder();
        RenewalTask renewalTask = new RenewalTask() {
            @Override
            public String getLockKey() {
                return lockKey;
            }

            @Override
            public void run() {
                try {
                    long actualLeaseTime = useWatchDog ? DEFAULT_LEASE_TIME : leaseTime;
                    LocalDateTime newExpire = LocalDateTime.now().plus(actualLeaseTime, ChronoUnit.MILLIS);
                    int updateCount = lockMapper.extendLockExpire(lockKey, originalHolder, newExpire);

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

        watchdogManager.submitRenewalTask(
                lockKey,
                renewalTask,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
        log.debug("通过看门狗启动续期任务: {}，间隔: {}ms", lockKey, intervalMillis);
    }

    private void stopRenewalTask() {
        watchdogManager.cancelRenewalTask(lockKey);
    }

    private boolean isLockExpired(DistributedLock lock) {
        return lock.getExpireTime().isBefore(LocalDateTime.now());
    }

    private boolean isCurrentHolder(DistributedLock lock, String holder) {
        return holder.equals(lock.getHolder());
    }

    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException("不支持条件锁");
    }

    public void forceCleanThreadLocal() {
        holderThreadLocal.remove();
        reentrantCountThreadLocal.remove();
        log.trace("强制清理锁[{}]的ThreadLocal资源", lockKey);
    }
}