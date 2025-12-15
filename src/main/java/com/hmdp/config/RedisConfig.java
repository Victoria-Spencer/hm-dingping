package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

//    @Bean
    public RedissonClient redissonClient() {
        // 1.配置类
        Config config = new Config();
        // 配置单点地址
        config.useSingleServer().setAddress("redis://192.168.43.143:6379");
        // 2.创建客户端
        return Redisson.create(config);
    }

    /*@Bean
    public RedissonClient redissonClient1() {
        // 1.配置类
        Config config = new Config();
        // 配置单点地址
        config.useSingleServer().setAddress("redis://192.168.43.143:6380");
        // 2.创建客户端
        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient2() {
        // 1.配置类
        Config config = new Config();
        // 配置单点地址
        config.useSingleServer().setAddress("redis://192.168.43.143:6381");
        // 2.创建客户端
        return Redisson.create(config);
    }*/
}
