package com.hmdp.lock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("lock_sequence")
public class LockSequence {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String lockKey;          // 关联的锁标识
    private Long currentSequence;    // 当前最大序列号
}