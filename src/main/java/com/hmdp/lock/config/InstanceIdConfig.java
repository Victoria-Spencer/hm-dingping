package com.hmdp.lock.config;

import cn.hutool.core.lang.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InstanceIdConfig {
    @Bean
    public String instanceUUID() {
        // 类加载时生成一次，全局唯一，确保每台机器唯一
        String uuid = UUID.randomUUID().toString(true);
//        return UUID.randomUUID().toString(true)
        // TODO test
        System.out.println("instanceUUID:" + uuid);
        return uuid;
    }
}