package com.hmdp;

import com.hmdp.config.RedLockFactory;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.RedissonRedLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

@SpringBootTest
@Slf4j
public class RedissonTest {

    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private RedissonClient redissonClient1;
    @Autowired
    private RedissonClient redissonClient2;
/*    @Autowired
    private RedLockFactory redLockFactory;*/

//    private RedissonRedLock redLock;
    private RLock lock;

    @BeforeEach
    void setUp() {
//        redLock = redLockFactory.createRedLock("order");
        RLock lock1 = redissonClient.getLock("order");
        RLock lock2 = redissonClient1.getLock("order");
        RLock lock3 = redissonClient2.getLock("order");
        lock = redissonClient1.getMultiLock(lock1, lock2, lock3);
    }

    @Test
    void method1() throws InterruptedException {
        // 尝试获取锁
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!isLock) {
            log.error("获取锁失败 ... 1");
            return;
        }

        try {
            log.info("获取锁成功 ... 1");
            method2();
            log.info("开始执行业务 ... 1");
        } finally {
            log.warn("准备释放锁 ... 1");
            lock.unlock();
        }
    }

    @Test
    void method2() throws InterruptedException {
        // 尝试获取锁
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!isLock) {
            log.error("获取锁失败 ... 2");
            return;
        }

        try {
            log.info("获取锁成功 ... 2");
            log.info("开始执行业务 ... 2");
        } finally {
            log.warn("准备释放锁 ... 2");
            lock.unlock();
        }
    }
}
