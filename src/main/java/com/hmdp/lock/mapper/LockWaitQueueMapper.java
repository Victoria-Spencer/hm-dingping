package com.hmdp.lock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.lock.entity.LockWaitQueue;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LockWaitQueueMapper extends BaseMapper<LockWaitQueue> {
    // 添加等待序列
    void addToQueue(@Param("lockKey") String lockKey,
                    @Param("sequence") Long sequence,
                    @Param("instanceId") String instanceId,
                    @Param("createTime") LocalDateTime createTime,
                    @Param("expireTime") LocalDateTime expireTime);

    // 从队列中移除
    int removeFromQueue(@Param("lockKey") String lockKey, @Param("sequence") Long sequence);

    // 获取最小序列号
    Long selectMinSequence(@Param("lockKey") String lockKey);

    // 清理过期的等待记录
    int cleanExpired(@Param("lockKey") String lockKey, @Param("now") LocalDateTime now);

    // 查询实例是否存在特定等待序列
    boolean exists(@Param("lockKey") String lockKey, @Param("sequence") Long sequence, @Param("instanceId") String instanceId);
}