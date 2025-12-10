package com.hmdp.lock.listener;

import com.hmdp.lock.entity.LockNotify;
import com.hmdp.lock.mapper.LockNotifyMapper;
import com.hmdp.lock.mapper.LockSequenceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class LockNotifyListener {
    // 存储结构：lockKey -> (threadId -> CountDownLatch)
    private final ConcurrentMap<String, ConcurrentMap<String, CountDownLatch>> latchMap = new ConcurrentHashMap<>();
    // 存储每个锁的订阅状态
    private final ConcurrentMap<String, Boolean> subscribedMap = new ConcurrentHashMap<>();

    @Autowired
    private LockNotifyMapper notifyMapper;
    @Autowired
    private LockSequenceMapper sequenceMapper;

    /**
     * 订阅锁释放通知（传入线程标识）
     */
    public CountDownLatch subscribe(String lockKey, String threadId) {
        // 初始化二级Map（lockKey对应的所有线程）
        ConcurrentMap<String, CountDownLatch> threadLatches = latchMap.computeIfAbsent(lockKey, k -> new ConcurrentHashMap<>());
        // 初始化当前线程的信号量
        CountDownLatch latch = new CountDownLatch(1);
        threadLatches.put(threadId, latch);

        // 检查是否已订阅，避免重复启动监听线程
        subscribedMap.computeIfAbsent(lockKey, k -> {
            new Thread(() -> {
                try {
                    long lastSequence = sequenceMapper.selectCurrentSequence(lockKey);
                    while (subscribedMap.getOrDefault(lockKey, false)) {
                        // 查询最新通知
                        List<LockNotify> notifications = notifyMapper.selectByLockKeyAndSequence(lockKey, lastSequence + 1);
                        if (!notifications.isEmpty()) {
                            LockNotify notify = notifications.get(0);
                            lastSequence = notify.getSequence();
                            // 唤醒所有订阅该锁的线程（核心变更）
                            ConcurrentMap<String, CountDownLatch> currentThreadLatches = latchMap.get(lockKey);
                            if (currentThreadLatches != null && !currentThreadLatches.isEmpty()) {
                                for (CountDownLatch threadLatch : currentThreadLatches.values()) {
                                    threadLatch.countDown(); // 唤醒单个线程
                                }
                                log.debug("唤醒所有订阅锁[{}]的线程，共{}个", lockKey, currentThreadLatches.size());
                            }
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
     * 取消订阅（指定线程）
     */
    public void unsubscribe(String lockKey, String threadId) {
        ConcurrentMap<String, CountDownLatch> threadLatches = latchMap.get(lockKey);
        if (threadLatches != null) {
            threadLatches.remove(threadId);
            // 若二级Map为空，清理一级Map和订阅状态
            if (threadLatches.isEmpty()) {
                latchMap.remove(lockKey);
                subscribedMap.remove(lockKey);
            }
        }
    }

    /**
     * 定期清理过期线程（如每30秒）
     */
    @Scheduled(fixedRate = 30000)
    public void cleanExpiredThreads() {
        for (String lockKey : latchMap.keySet()) {
            ConcurrentMap<String, CountDownLatch> threadLatches = latchMap.get(lockKey);
            if (threadLatches == null) continue;

            // 遍历线程，移除已中断/失效的线程（可结合线程状态判断）
            threadLatches.keySet().removeIf(threadId -> {
                // 此处可根据实际场景判断线程是否过期（例如：通过线程ID查询线程状态）
                boolean isExpired = false;
                // 示例：假设threadId包含线程ID，可尝试判断线程是否存活
                // ...
                return isExpired;
            });

            // 清理后若为空，停止监听
            if (threadLatches.isEmpty()) {
                latchMap.remove(lockKey);
                subscribedMap.remove(lockKey);
            }
        }
    }
}