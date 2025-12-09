package com.hmdp.lock.watchdog;

/**
 * 续期任务接口
 */
public interface RenewalTask extends Runnable {
    String getLockKey();
}