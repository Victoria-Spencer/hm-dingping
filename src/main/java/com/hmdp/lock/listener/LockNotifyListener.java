package com.hmdp.lock.listener;

import com.hmdp.lock.entity.LockNotify;
import com.hmdp.lock.entity.LockWaitQueue;
import com.hmdp.lock.mapper.LockNotifyMapper;
import com.hmdp.lock.mapper.LockSequenceMapper;
import com.hmdp.lock.mapper.LockWaitQueueMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@Slf4j
public class LockNotifyListener {
    // 本地缓存：只存储当前实例的等待信号量
    private final ConcurrentMap<String, ConcurrentMap<Long, CountDownLatch>> localLatchMap = new ConcurrentHashMap<>();
    // 存储每个锁的订阅状态
    private final ConcurrentMap<String, Boolean> subscribedMap = new ConcurrentHashMap<>();
    // 实例ID
    private final String instanceId = java.util.UUID.randomUUID().toString();

    @Autowired
    private LockNotifyMapper notifyMapper;
    @Autowired
    private LockSequenceMapper sequenceMapper;
    @Autowired
    private LockWaitQueueMapper waitQueueMapper;

    /**
     * 订阅锁释放通知（传入序列号）
     */
    public CountDownLatch subscribe(String lockKey, long sequence, long leaseTime) {
        // 初始化本地信号量
        ConcurrentMap<Long, CountDownLatch> sequenceLatches = localLatchMap.computeIfAbsent(lockKey, k -> new ConcurrentHashMap<>());
        CountDownLatch latch = new CountDownLatch(1);
        sequenceLatches.put(sequence, latch);

        // 计算等待队列过期时间：设置为锁租期的1.5倍（确保在锁过期前不会提前失效）
        LocalDateTime now = LocalDateTime.now();
        long waitQueueExpireMillis = (long) (leaseTime * 1.5);
        waitQueueMapper.addToQueue(
                lockKey,
                sequence,
                instanceId,
                now,
                now.plus(waitQueueExpireMillis, ChronoUnit.MILLIS) // 基于租期动态计算
        );

        // 检查是否已订阅，避免重复启动监听线程
        subscribedMap.computeIfAbsent(lockKey, k -> {
            new Thread(() -> {
                try {
                    // 初始序列号从0开始
                    long lastSequence = 0;
                    while (subscribedMap.getOrDefault(lockKey, false)) {
                        // 查询最新通知（序列号大于lastSequence的）
                        List<LockNotify> notifications = notifyMapper.selectByLockKeyAndSequence(lockKey, lastSequence + 1);
                        if (!notifications.isEmpty()) {
                            LockNotify notify = notifications.get(0);
                            lastSequence = notify.getSequence();

                            // 检查是否是当前实例的等待序列
                            ConcurrentMap<Long, CountDownLatch> currentLatches = localLatchMap.get(lockKey);
                            if (currentLatches != null) {
                                CountDownLatch targetLatch = currentLatches.remove(lastSequence);
                                if (targetLatch != null) {
                                    targetLatch.countDown(); // 唤醒当前序列号对应的线程
                                    log.debug("唤醒锁[{}]的等待线程，序列号:{}", lockKey, lastSequence);
                                }
                            }

                            // 从分布式队列中移除已通知的序列号
                            waitQueueMapper.removeFromQueue(lockKey, lastSequence);
                        }
                        // 降低查询频率
                        TimeUnit.MILLISECONDS.sleep(50);
                    }
                } catch (Exception e) {
                    log.error("锁通知监听异常: {}", lockKey, e);
                }
            }, "lock-notify-listener-" + lockKey).start();
            return true;
        });

        return latch;
    }

    /**
     * 取消订阅（指定序列号）
     */
    public void unsubscribe(String lockKey, long sequence) {
        // 移除本地信号量
        ConcurrentMap<Long, CountDownLatch> sequenceLatches = localLatchMap.get(lockKey);
        if (sequenceLatches != null) {
            sequenceLatches.remove(sequence);

            // 若本地缓存为空，清理并取消订阅
            if (sequenceLatches.isEmpty()) {
                localLatchMap.remove(lockKey);
                subscribedMap.remove(lockKey);
            }
        }

        // 从分布式队列中移除
        waitQueueMapper.removeFromQueue(lockKey, sequence);
    }

    /**
     * 获取全局最小的等待序列号
     */
    public Long getMinWaitingSequence(String lockKey) {
        // 先清理过期记录
        waitQueueMapper.cleanExpired(lockKey, LocalDateTime.now());
        // 查询全局最小序列号
        return waitQueueMapper.selectMinSequence(lockKey);
    }

    /**
     * 定期清理过期序列号
     */
    @Scheduled(fixedRate = 30000)
    public void cleanExpiredSequences() {
        LocalDateTime now = LocalDateTime.now();

        // 清理分布式队列中的过期记录
        for (String lockKey : localLatchMap.keySet()) {
            waitQueueMapper.cleanExpired(lockKey, now);
        }

        // 清理本地缓存中已过期且不在队列中的记录
        for (String lockKey : localLatchMap.keySet()) {
            ConcurrentMap<Long, CountDownLatch> sequenceLatches = localLatchMap.get(lockKey);
            if (sequenceLatches == null) continue;

            List<Long> toRemove = sequenceLatches.keySet().stream()
                    .filter(seq -> !waitQueueMapper.exists(lockKey, seq, instanceId))
                    .collect(Collectors.toList());

            toRemove.forEach(seq -> sequenceLatches.remove(seq));

            if (sequenceLatches.isEmpty()) {
                localLatchMap.remove(lockKey);
                subscribedMap.remove(lockKey);
            }
        }
    }
}