package com.hmdp.lock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.lock.entity.LockNotify;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.time.LocalDateTime;

@Repository
public interface LockNotifyMapper extends BaseMapper<LockNotify> {
    // 查询指定锁的最新通知序号
    Long selectMaxSequenceByLockKey(@Param("lockKey") String lockKey);

    // 查询指定序号及之后的通知
    List<LockNotify> selectByLockKeyAndSequence(@Param("lockKey") String lockKey, @Param("sequence") Long sequence);

    // 删除指定锁的历史通知
    int deleteByLockKey(@Param("lockKey") String lockKey);

    // 插入通知记录
    void insertNotify(LockNotify notify);
}