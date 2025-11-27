package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class MvcConfig implements WebMvcConfigurer {

    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns("/user/code")
                .excludePathPatterns("/user/login")
                .excludePathPatterns("blog/hot")
                .excludePathPatterns("/shop/**")
                .excludePathPatterns("/shop-type/**")
                .excludePathPatterns("/upload/**")
                .excludePathPatterns("/voucher/**");
    }
}
