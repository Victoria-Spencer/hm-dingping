package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1735689600L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BIT = 32;

    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 获取当前日期，精确到天
        String date = now.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接，返回
        return (timeStamp << COUNT_BIT) | count;
    }

    /*public static void main(String args[]) {
        LocalDateTime time = LocalDateTime.of(2025, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second：" + second);
    }*/
}
