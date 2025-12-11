package com.hmdp.lock.core;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

/**
 * 分布式锁接口（模仿Lock接口，用于统一锁操作）
 */
public interface DLock {
    void lock();

    void lock(long leaseTime, TimeUnit unit) throws InterruptedException;

    boolean tryLock();

    boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException;

    void unlock();

    void lockInterruptibly() throws InterruptedException;


    Condition newCondition();
}