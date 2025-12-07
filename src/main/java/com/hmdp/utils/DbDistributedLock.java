package com.hmdp.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.DistributedLock;
import com.hmdp.exception.LockAcquireFailedException;
import com.hmdp.mapper.DistributedLockMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class DbDistributedLock {
    private final DistributedLockMapper lockMapper;
    private final DistributedLockAcquirer lockAcquirer;
    private final StringRedisTemplate stringRedisTemplate;

    // 本地缓存holder（减少Redis访问）
    private final ThreadLocal<String> threadLocalHolder = new ThreadLocal<>();
    // Redis中holder的key前缀（格式：lock:holder:{会话标识}）
    private static final String HOLDER_REDIS_KEY_PREFIX = "lock:holder:";

    // 构造函数注入依赖
    public DbDistributedLock(DistributedLockMapper lockMapper,
                             DistributedLockAcquirer lockAcquirer,
                             StringRedisTemplate stringRedisTemplate) {
        this.lockMapper = lockMapper;
        this.lockAcquirer = lockAcquirer;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 锁过期时间（30秒）
    private static final int LOCK_EXPIRE_SEC = 30;
    // 续期间隔（10秒）
    private static final int RENEWAL_INTERVAL_SEC = 10;
    // holder在Redis中的过期时间（60秒，需长于锁过期时间）
    private static final int HOLDER_TTL_SEC = 60;

    /**
     * 获取全局唯一会话标识（核心：确保同一用户操作链路在不同实例中标识一致）
     * 实现逻辑：用户ID + 全局会话ID（从请求头获取）
     */
    private String getSessionId() {
        // 1. 获取当前用户（从UserHolder，需确保登录状态）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new IllegalStateException("当前用户未登录，无法获取会话标识");
        }
        Long userId = user.getId();

        // 2. 获取全局会话ID（从请求头X-Session-Id，由网关或前端生成）
        ServletRequestAttributes requestAttributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            throw new IllegalStateException("当前请求上下文不存在，无法获取会话标识");
        }
        HttpServletRequest request = requestAttributes.getRequest();
        String sessionId = request.getHeader("X-Session-Id");
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalStateException("请求头X-Session-Id不存在，无法获取会话标识");
        }

        // 3. 生成会话标识（用户ID+会话ID，确保全局唯一）
        return userId + ":" + sessionId;
    }

    /**
     * 获取holder（优先本地缓存，其次Redis，不存在则生成并存储）
     */
    private String getHolder() {
        // 1. 先查本地ThreadLocal缓存
        String holder = threadLocalHolder.get();
        if (holder != null) {
            return holder;
        }

        // 2. 本地无则查Redis
        String sessionId = getSessionId();
        String redisKey = HOLDER_REDIS_KEY_PREFIX + sessionId;
        holder = stringRedisTemplate.opsForValue().get(redisKey);

        // 3. Redis无则生成新holder并原子存入Redis（避免并发冲突）
        if (holder == null) {
            holder = UUID.randomUUID().toString();
            // 使用SETNX确保只有一个实例能生成holder
            Boolean success = stringRedisTemplate.opsForValue()
                    .setIfAbsent(redisKey, holder, HOLDER_TTL_SEC, TimeUnit.SECONDS);
            if (success != null && success) {
                log.debug("会话{}生成新holder：{}", sessionId, holder);
            } else {
                // 并发场景下可能被其他实例生成，重新从Redis获取
                holder = stringRedisTemplate.opsForValue().get(redisKey);
                log.debug("会话{}从Redis获取已有holder：{}", sessionId, holder);
            }
        }

        // 4. 存入本地缓存，减少后续Redis访问
        threadLocalHolder.set(holder);
        return holder;
    }

    /**
     * 带锁执行业务（支持跨实例重入）
     */
    public <T> T executeWithLock(String lockKey, BusinessTask<T> business) throws Exception {
        String holder = getHolder(); // 关键：从Redis+本地缓存获取holder
        LocalDateTime expireTime = LocalDateTime.now().plusSeconds(LOCK_EXPIRE_SEC);
        Thread renewalThread = null;
        boolean isReleaseCompletely = false;

        try {
            // 获取锁（支持重入）
            lockAcquirer.acquireLock(lockKey, holder, expireTime);
            // 启动续期线程
            renewalThread = startLockRenewal(lockKey, holder);
            // 执行业务
            return business.run();
        } catch (DuplicateKeyException e) {
            log.error("并发创建锁记录失败，lockKey={}", lockKey, e);
            throw new LockAcquireFailedException("锁被其他线程抢占");
        } catch (LockAcquireFailedException e) {
            throw e;
        } catch (Exception e) {
            log.error("分布式锁执行业务失败，lockKey={}", lockKey, e);
            throw e;
        } finally {
            // 终止续期线程
            if (renewalThread != null && !renewalThread.isInterrupted()) {
                renewalThread.interrupt();
                log.debug("锁续期线程已终止，lockKey={}", lockKey);
            }
            // 释放锁并判断是否彻底释放
            isReleaseCompletely = releaseLock(lockKey, holder);

            // 彻底释放时清理本地缓存和Redis中的holder
            if (isReleaseCompletely) {
                threadLocalHolder.remove();
                String sessionId = getSessionId();
                stringRedisTemplate.delete(HOLDER_REDIS_KEY_PREFIX + sessionId);
                log.debug("会话{}的holder已清理：{}", sessionId, holder);
            }
        }
    }

    /**
     * 释放锁（支持重入释放）
     * @return 是否彻底释放（重入次数=0）
     */
    private boolean releaseLock(String lockKey, String holder) {
        try {
            // 加行锁查询当前锁状态（防止并发修改）
            DistributedLock lock = lockMapper.getLockWithExLock(lockKey);

            // 锁不存在或持有者不匹配，直接返回
            if (lock == null || !lock.getHolder().equals(holder)) {
                log.warn("释放失败：锁不存在或持有者不匹配，lockKey={}, holder={}", lockKey, holder);
                return true; // 视为彻底释放，清理holder
            }

            // 计算新重入次数
            int newReentrantCount = lock.getReentrantCount() - 1;
            LocalDateTime newExpire = LocalDateTime.now().plusSeconds(LOCK_EXPIRE_SEC);

            if (newReentrantCount > 0) {
                // 重入次数>0：仅减少计数并续期
                int updateCount = lockMapper.decrementReentrantCount(lockKey, holder, newExpire);
                if (updateCount > 0) {
                    log.debug("锁重入次数减少，lockKey={}, holder={}, 剩余重入次数={}",
                            lockKey, holder, newReentrantCount);
                } else {
                    log.warn("重入次数更新失败，lockKey={}, holder={}", lockKey, holder);
                }
                return false; // 未彻底释放，不清理holder
            } else {
                // 重入次数=0：彻底删除锁
                int deleteCount = lockMapper.delete(new QueryWrapper<DistributedLock>()
                        .eq("lock_key", lockKey)
                        .eq("holder", holder)
                );
                if (deleteCount > 0) {
                    log.debug("锁彻底释放，lockKey={}, holder={}", lockKey, holder);
                } else {
                    log.warn("锁删除失败，lockKey={}, holder={}", lockKey, holder);
                }
                return true; // 彻底释放，需要清理holder
            }
        } catch (Exception e) {
            log.error("锁释放过程异常，lockKey={}, holder={}", lockKey, holder, e);
            return true; // 异常时强制清理holder，避免内存泄漏
        }
    }

    /**
     * 启动锁续期线程（防止锁过期）
     */
    private Thread startLockRenewal(String lockKey, String holder) {
        Thread renewalThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // 每隔10秒续期一次
                    TimeUnit.SECONDS.sleep(RENEWAL_INTERVAL_SEC);
                    LocalDateTime newExpire = LocalDateTime.now().plusSeconds(LOCK_EXPIRE_SEC);
                    int updateCount = lockMapper.extendLockExpire(lockKey, holder, newExpire);
                    if (updateCount == 0) {
                        log.debug("锁续期失败，lockKey={}, holder={}（锁已释放/被抢占）", lockKey, holder);
                        break;
                    }
                    log.debug("锁续期成功，lockKey={}, newExpire={}", lockKey, newExpire);
                }
            } catch (InterruptedException e) {
                log.debug("锁续期线程被中断，lockKey={}", lockKey);
                Thread.currentThread().interrupt(); // 保留中断状态
            } catch (Exception e) {
                log.error("锁续期线程异常，lockKey={}", lockKey, e);
            }
        }, "lock-renewal-" + lockKey);

        renewalThread.setDaemon(true); // 守护线程，随主线程退出
        renewalThread.start();
        return renewalThread;
    }

    /**
     * 业务任务函数式接口
     */
    @FunctionalInterface
    public interface BusinessTask<T> {
        T run() throws Exception;
    }
}