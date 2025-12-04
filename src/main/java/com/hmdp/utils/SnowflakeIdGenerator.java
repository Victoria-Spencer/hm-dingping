package com.hmdp.utils;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.net.NetUtil;
import cn.hutool.core.util.IdUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 雪花算法工具类（全局唯一ID生成器）
 * 注意：通过@Component注册为Spring Bean，确保配置注入生效
 */
@Component // 关键：注册为Spring Bean，使@Value和@PostConstruct生效
public class SnowflakeIdGenerator {

    @Value("${snowflake.datacenter-id:1}")
    private long datacenterId; // 数据中心ID（配置/默认值，无随机）
    @Value("${snowflake.worker-id:-1}")
    private long workerId;     // 机器ID（优先配置，-1则自动生成）

    // 静态变量存储最终可用的参数和Snowflake实例
    private static long finalWorkerId;
    private static long finalDatacenterId;
    private static Snowflake snowflake;

    // Spring初始化完成后执行（核心：保证注入完成后再初始化静态变量）
    @PostConstruct
    public void init() {
        // 初始化机器ID：配置优先，无配置则基于IP生成
        finalWorkerId = (workerId == -1) ? generateWorkerId() : workerId;
        // 初始化数据中心ID：直接用注入值（无随机）
        finalDatacenterId = datacenterId;

        // 校验参数范围（0~31），避免初始化失败
        validateIdRange(finalWorkerId, "机器ID");
        validateIdRange(finalDatacenterId, "数据中心ID");

        // 创建Snowflake实例（仅创建一次，复用）
        snowflake = IdUtil.createSnowflake(finalWorkerId, finalDatacenterId);
    }

    // 私有化构造方法，禁止外部实例化
    private SnowflakeIdGenerator() {}

    // ============================== 辅助方法 ==============================
    /**
     * 基于IP生成机器ID（容器化环境兜底）
     */
    private long generateWorkerId() {
        try {
            // 优先获取宿主机IP，失败则返回默认值0
            return Math.abs(NetUtil.ipv4ToLong(NetUtil.getLocalhostStr()) % 32);
        } catch (Exception e) {
            return 0L; // 兜底值，保证不崩溃
        }
    }

    /**
     * 校验ID范围（0~31）
     */
    private void validateIdRange(long id, String name) {
        if (id < 0 || id > 31) {
            throw new IllegalStateException(name + "必须在0~31范围内，当前值：" + id);
        }
    }

    // ============================== 业务方法 ==============================
    public static String generatePreOrderSn() {
        return String.valueOf(snowflake.nextId());
    }

    public static String generateOrderSn() {
        return "ORD" + snowflake.nextId();
    }
}