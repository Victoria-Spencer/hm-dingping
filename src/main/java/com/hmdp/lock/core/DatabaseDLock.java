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
    private final LockWaitQueueMapper waitQueueMapper;
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

    @Setter
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

            // 基于序列号订阅等待
            boolean notified = subscribeAndWait(remaining);
            if (!notified) {
                return false;
            }
        }
    }

    private boolean subscribeAndWait(long timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout;

        // 初始化序列号并生成预期序列号（原子递增）
        sequenceMapper.initSequenceIfAbsent(lockKey);
        long expectedSeq = sequenceMapper.incrementAndGet(lockKey);
        log.debug("锁[{}]订阅序列号:{}，等待超时时间:{}ms", lockKey, expectedSeq, timeout);

        // 订阅指定序列号
        CountDownLatch latch = notifyListener.subscribe(lockKey, expectedSeq);
        try {
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    log.debug("锁[{}]等待总超时，序列号:{}", lockKey, expectedSeq);
                    return false;
                }

                // 等待通知或剩余时间超时
                boolean notified = latch.await(remaining, TimeUnit.MILLISECONDS);
                if (notified) {
                    // 检查是否是目标序列号的通知
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
            // 取消订阅
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
                throw new IllegalMonitorStateException("锁重入计数异常，无法释放: " + lockKey);
            }

            // 重入计数减1
            if (currentCount > 1) {
                // 非最后一次释放，仅减少计数并续期
                LocalDateTime newExpire = LocalDateTime.now().plus(leaseTime, ChronoUnit.MILLIS);
                int updateCount = lockMapper.decrementReentrantCount(lockKey, holder, newExpire);
                if (updateCount > 0) {
                    setReentrantCount(currentCount - 1);
                    log.debug("锁[{}]重入计数减少，当前计数: {}", lockKey, currentCount - 1);
                } else {
                    throw new IllegalMonitorStateException("锁持有者不匹配，无法释放: " + lockKey);
                }
            } else {
                // 最后一次释放，删除锁记录
                int deleteCount = lockMapper.delete(
                        new QueryWrapper<DistributedLock>()
                                .eq("lock_key", lockKey)
                                .eq("holder", holder)
                );
                if (deleteCount > 0) {
                    setReentrantCount(0);
                    // 停止看门狗续期
                    if (useWatchDog) {
                        watchdogManager.cancelRenewalTask(lockKey);
                    }
                    log.debug("锁[{}]完全释放", lockKey);

                    // ====== 释放锁成功后，生成通知给下一个等待者 ======
                    // 获取全局最小等待序列号
                    Long minSequence = notifyListener.getMinWaitingSequence(lockKey);
                    if (minSequence != null) {
                        // 插入通知记录
                        LockNotify notify = new LockNotify();
                        notify.setLockKey(lockKey);
                        notify.setSequence(minSequence);
                        notify.setNotifyTime(LocalDateTime.now());
                        notifyMapper.insertNotify(lockKey, minSequence, LocalDateTime.now());
                        log.debug("锁[{}]释放，通知下一个等待者，序列号:{}", lockKey, minSequence);
                    }
                    // ===================================================
                } else {
                    throw new IllegalMonitorStateException("锁持有者不匹配或已释放，无法释放: " + lockKey);
                }
            }
        } catch (Exception e) {
            log.error("释放锁异常: {}", lockKey, e);
            throw new RuntimeException("释放锁失败: " + lockKey, e);
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
            // 用匿名内部类实现 RenewalTask，显式覆盖所有抽象方法
            RenewalTask task = new RenewalTask() {
                @Override
                public void run() { // 实现 Runnable 的 run() 方法
                    try {
                        LocalDateTime newExpire = LocalDateTime.now().plus(DEFAULT_LEASE_TIME, ChronoUnit.MILLIS);
                        int update = lockMapper.extendLockExpire(lockKey, getHolder(), newExpire);
                        if (update <= 0) {
                            throw new LockRenewalFailedException("锁续期失败: " + lockKey);
                        }
                        log.debug("锁[{}]续期成功，新过期时间:{}", lockKey, newExpire);
                    } catch (Exception e) {
                        log.error("锁[{}]续期异常", lockKey, e);
                        throw new LockRenewalFailedException("锁续期异常: " + lockKey);
                    }
                }

                @Override
                public String getLockKey() { // 实现 RenewalTask 自身的 getLockKey() 方法
                    return lockKey; // 返回当前锁的 key
                }
            };
            // 提交续期任务
            watchdogManager.submitRenewalTask(lockKey, task, leaseMillis / 3, leaseMillis / 3, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelRenewal() {
        watchdogManager.cancelRenewalTask(lockKey);
    }

    public void forceCleanThreadLocal() {
        holderThreadLocal.remove();
        reentrantCountThreadLocal.remove();
    }

    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException("分布式锁不支持Condition");
    }
}