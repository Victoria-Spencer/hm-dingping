package com.hmdp.lock.core;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.lock.entity.DistributedLock;
import com.hmdp.lock.entity.LockNotify;
import com.hmdp.lock.exception.LockAcquireFailedException;
import com.hmdp.lock.exception.LockRenewalFailedException;
import com.hmdp.lock.listener.LockNotifyListener;
import com.hmdp.lock.mapper.DistributedLockMapper;
import com.hmdp.lock.mapper.LockNotifyMapper;
import com.hmdp.lock.mapper.LockSequenceMapper;
import com.hmdp.lock.mapper.LockWaitQueueMapper;
import com.hmdp.lock.watchdog.RenewalTask;
import com.hmdp.lock.watchdog.WatchdogManager;
import jdk.nashorn.internal.objects.annotations.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

@Component
@Slf4j
@Scope("prototype")  // 每次获取Bean时创建新实例
public class DatabaseDLock implements DLock {

    private final String lockKey;
    private final DistributedLockMapper lockMapper;
    private final LockNotifyMapper notifyMapper;
    private final LockSequenceMapper sequenceMapper;
    private final LockWaitQueueMapper waitQueueMapper;
    private final String instanceUUID;
    private final ThreadLocal<String> holderThreadLocal = new ThreadLocal<>();
    private final ThreadLocal<Integer> reentrantCountThreadLocal = new ThreadLocal<>();
    private final WatchdogManager watchdogManager;
    private static final long DEFAULT_LEASE_TIME = 30 * 1000;
    private static final long NOTIFY_CHECK_INTERVAL_MS = 50;
    // 续期间隔（为租期的1/3，确保在过期前完成续期）
    private static final long RENEWAL_INTERVAL_RATIO = 3;

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
                         LockWaitQueueMapper waitQueueMapper,
                         String instanceUUID,
                         WatchdogManager watchdogManager) {
        this.lockKey = lockKey;
        this.lockMapper = lockMapper;
        this.notifyMapper = notifyMapper;
        this.sequenceMapper = sequenceMapper;
        this.waitQueueMapper = waitQueueMapper;
        this.instanceUUID = instanceUUID;
        this.watchdogManager = watchdogManager;
    }

    private String getHolder() {
        String holder = holderThreadLocal.get();
        if (holder == null) {
            long threadId = Thread.currentThread().getId();
            holder = instanceUUID + ":" + threadId;
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

            boolean notified = subscribeAndWait(remaining);
            if (!notified) {
                return false;
            }
        }
    }

    private boolean subscribeAndWait(long timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout;

        sequenceMapper.initSequenceIfAbsent(lockKey);
        long expectedSeq = sequenceMapper.incrementAndGet(lockKey);
        log.debug("锁[{}]订阅序列号:{}，等待超时时间:{}ms", lockKey, expectedSeq, timeout);

        CountDownLatch latch = notifyListener.subscribe(lockKey, expectedSeq);
        try {
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    log.debug("锁[{}]等待总超时，序列号:{}", lockKey, expectedSeq);
                    return false;
                }

                boolean notified = latch.await(remaining, TimeUnit.MILLISECONDS);
                if (notified) {
                    List<LockNotify> notifications = notifyMapper.selectByLockKeyAndSequence(lockKey, expectedSeq);
                    if (!notifications.isEmpty()) {
                        log.debug("锁[{}]收到有效通知，序列号:{}", lockKey, expectedSeq);
                        return true;
                    }
                    log.debug("锁[{}]收到无效通知，继续等待目标序列号:{}", lockKey, expectedSeq);
                } else {
                    log.debug("锁[{}]通知等待超时，序列号:{}，剩余时间:{}ms", lockKey, expectedSeq, remaining);
                    return false;
                }
            }
        } finally {
            notifyListener.unsubscribe(lockKey, expectedSeq);
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
                log.warn("释放锁时重入计数异常: {}，计数: {}", lockKey, currentCount);
                return;
            }

            if (self == null) {
                throw new IllegalMonitorStateException("分布式锁self引用未初始化: " + lockKey);
            }

            if (currentCount > 1) {
                boolean released = self.tryDecrementReentrantCount();
                if (released) {
                    setReentrantCount(currentCount - 1);
                    log.debug("锁重入计数减少: {}，当前计数: {}", lockKey, currentCount - 1);
                }
                return;
            }

            boolean released = self.tryRelease();
            if (released) {
                setReentrantCount(0);
                stopRenewal();
                notifyWaiters();
                log.debug("锁释放成功: {}", lockKey);
            } else {
                log.warn("锁释放失败，可能已过期或被其他线程获取: {}", lockKey);
            }
        } catch (Exception e) {
            log.error("释放锁异常: {}", lockKey, e);
            forceCleanThreadLocal();
            throw new RuntimeException("释放锁失败: " + lockKey, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean tryDecrementReentrantCount() {
        String holder = getHolder();
        LocalDateTime newExpire = LocalDateTime.now().plus(leaseTime, ChronoUnit.MILLIS);
        int updateCount = lockMapper.decrementReentrantCount(lockKey, holder, newExpire);
        return updateCount > 0;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean tryRelease() {
        String holder = getHolder();
        int deleteCount = lockMapper.delete(new QueryWrapper<DistributedLock>()
                .eq("lock_key", lockKey)
                .eq("holder", holder));
        return deleteCount > 0;
    }

    private void notifyWaiters() {
        try {
            long newSeq = sequenceMapper.incrementAndGet(lockKey);
            LockNotify notify = new LockNotify();
            notify.setLockKey(lockKey);
            notify.setSequence(newSeq);
            notify.setNotifyTime(LocalDateTime.now());
            notifyMapper.insertNotify(notify);
            log.debug("锁[{}]通知等待队列，新序列号:{}", lockKey, newSeq);
        } catch (Exception e) {
            log.error("通知等待队列异常: {}", lockKey, e);
        }
    }

    private boolean isLockExpired(DistributedLock lock) {
        return lock.getExpireTime().isBefore(LocalDateTime.now());
    }

    private boolean isCurrentHolder(DistributedLock lock, String holder) {
        return holder.equals(lock.getHolder());
    }

    private void startRenewalIfNeeded(long leaseMillis) {
        if (useWatchDog) {
            // 创建续期任务
            // 启动续期任务时传入holder
            String currentLockHolder = getHolder(); // 原业务线程的holder
            RenewalTask renewalTask = new RenewalTask() {
                @Override
                public void run() {
                    try {
                        LocalDateTime newExpire = LocalDateTime.now().plus(leaseMillis, ChronoUnit.MILLIS);
                        int updateCount = lockMapper.extendLockExpire(lockKey, currentLockHolder, newExpire);
                        if (updateCount <= 0) {
                            throw new LockRenewalFailedException("锁续期失败: " + lockKey);
                        }
                        log.debug("锁[{}]续期成功，新过期时间: {}", lockKey, newExpire);
                    } catch (Exception e) {
                        log.error("锁[{}]续期异常", lockKey, e);
                        throw new LockRenewalFailedException("锁续期异常: " + lockKey, e);
                    }
                }

                @Override
                public String getLockKey() {
                    return lockKey;
                }
            };

            // 计算续期间隔（租期的1/3）
            long renewalInterval = leaseMillis / RENEWAL_INTERVAL_RATIO;
            watchdogManager.submitRenewalTask(
                    lockKey,
                    renewalTask,
                    renewalInterval * 2, // 立即开始
                    renewalInterval,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void stopRenewal() {
        if (useWatchDog) {
            watchdogManager.cancelRenewalTask(lockKey);
        }
    }

    public void forceCleanThreadLocal() {
        holderThreadLocal.remove();
        reentrantCountThreadLocal.remove();
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        throw new UnsupportedOperationException("未实现可中断锁");
    }

    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException("未实现Condition");
    }
}