package com.hmdp.lock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.lock.entity.LockSequence;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LockSequenceMapper extends BaseMapper<LockSequence> {

    /**
     * 初始化锁的序列号记录（若不存在）
     * @param lockKey 锁标识
     */
    void initSequenceIfAbsent(@Param("lockKey") String lockKey);

    /**
     * 查询当前序列号
     * @param lockKey 锁标识
     * @return 当前序列号
     */
    Long selectCurrentSequence(@Param("lockKey") String lockKey);

    /**
     * 原子递增序列号并返回最新值
     * @param lockKey 锁标识
     * @return 递增后的序列号
     */
    Long incrementAndGet(@Param("lockKey") String lockKey);
}