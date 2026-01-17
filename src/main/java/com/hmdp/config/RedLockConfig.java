package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.RedissonRedLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

//@Configuration
public class RedLockConfig {

    // 初始化多个独立的 Redis 客户端（私有方法，内部调用）
    @Bean
    public List<RedissonClient> redissonClients() {
        List<RedissonClient> clients = new ArrayList<>();

        // 第一个独立 Redis 节点配置（根据实际环境修改地址和密码）
        Config config1 = new Config();
        config1.useSingleServer().setAddress("redis://192.168.43.143:6379"); // 节点1地址
        clients.add(Redisson.create(config1));

        // 第二个独立 Redis 节点配置
        Config config2 = new Config();
        config2.useSingleServer().setAddress("redis://192.168.43.143:6380"); // 节点2地址
        clients.add(Redisson.create(config2));

        // 第三个独立 Redis 节点配置
        Config config3 = new Config();
        config3.useSingleServer().setAddress("redis://192.168.43.143:6381"); // 节点3地址
        clients.add(Redisson.create(config3));

        return clients;
    }
}