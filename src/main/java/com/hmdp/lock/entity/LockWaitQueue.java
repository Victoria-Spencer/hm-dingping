package com.hmdp.lock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("lock_wait_queue")
public class LockWaitQueue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String lockKey;      // 关联的锁标识
    private Long sequence;       // 等待的序列号
    private String instanceId;   // 实例ID
    private LocalDateTime createTime; // 创建时间
    private LocalDateTime expireTime; // 过期时间
}