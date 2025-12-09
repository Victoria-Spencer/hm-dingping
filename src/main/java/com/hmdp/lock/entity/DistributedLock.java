package com.hmdp.lock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("distributed_lock")
public class DistributedLock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String lockKey;      // 锁唯一标识
    private String holder;       // 锁持有者标识(实例UUID+线程ID)
    private LocalDateTime expireTime; // 锁过期时间
    private Integer reentrantCount; // 重入次数
}