package com.hmdp.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.DistributedLock;
import com.hmdp.exception.LockAcquireFailedException;
import com.hmdp.mapper.DistributedLockMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@Slf4j
public class DistributedLockAcquirer {
    private final DistributedLockMapper lockMapper;

    public DistributedLockAcquirer(DistributedLockMapper lockMapper) {
        this.lockMapper = lockMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void acquireLock(String lockKey, String holder, LocalDateTime expireTime) {
        DistributedLock lock = lockMapper.getLockWithExLock(lockKey);

        if (lock == null) {
            // 锁不存在：创建新锁，重入次数初始化为1
            lock = new DistributedLock();
            lock.setLockKey(lockKey);
            lock.setHolder(holder);
            lock.setExpireTime(expireTime);
            lock.setReentrantCount(1); // 首次获取，计数1
            lockMapper.insert(lock);
            log.debug("创建新锁成功，lockKey={}, holder={}, 重入次数=1", lockKey, holder);
        } else {
            if (lock.getExpireTime().isBefore(LocalDateTime.now())) {
                // 锁已过期：强制抢占，重入次数重置为1
                int updateCount = lockMapper.update(
                        new DistributedLock() {{
                            setHolder(holder);
                            setExpireTime(expireTime);
                            setReentrantCount(1); // 抢占后计数1
                        }},
                        new QueryWrapper<DistributedLock>()
                                .eq("lock_key", lockKey)
                                .le("expire_time", LocalDateTime.now())
                );
                if (updateCount == 0) {
                    throw new LockAcquireFailedException("锁被其他线程抢占");
                }
                log.debug("抢占过期锁成功，lockKey={}, holder={}, 重入次数=1", lockKey, holder);
            } else if (lock.getHolder().equals(holder)) {
                // 锁未过期且是当前持有者：重入，计数+1
                int updateCount = lockMapper.incrementReentrantCount(lockKey, holder, expireTime);
                if (updateCount == 0) {
                    throw new LockAcquireFailedException("重入失败，锁状态已变更");
                }
                log.debug("锁重入成功，lockKey={}, holder={}, 重入次数={}",
                        lockKey, holder, lock.getReentrantCount() + 1);
            } else {
                // 锁被其他持有者持有：获取失败
                throw new LockAcquireFailedException("锁已被其他线程持有（holder=" + lock.getHolder() + "）");
            }
        }
    }
}