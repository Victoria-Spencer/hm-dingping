package com.hmdp.lock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("lock_notify")
public class LockNotify {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String lockKey;      // 关联的锁标识
    private Long sequence;       // 通知序号
    private LocalDateTime notifyTime; // 通知时间
}