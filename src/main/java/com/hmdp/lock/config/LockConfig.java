package com.hmdp.lock.config;

import com.hmdp.lock.factoryBean.DatabaseDLockFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LockConfig {

    @Bean
    public DatabaseDLockFactoryBean databaseDLockFactoryBean() {
        return new DatabaseDLockFactoryBean(/* 必要的构造参数 */);
    }
}