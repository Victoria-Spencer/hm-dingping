package com.hmdp.exceptionHandler;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * 捕获项目中所有未手动处理的异常，返回标准化JSON响应
 */
@Slf4j // 打印日志，便于排查异常
@RestControllerAdvice // 全局生效，返回JSON
public class GlobalExceptionHandler {

    // 处理所有未捕获的运行时异常（最终兜底）
    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error("运行时异常：", e);
        return Result.fail("运行时异常：" + e.getMessage());
    }

    // 处理所有其他异常（最顶层异常，兜底中的兜底）
    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e) {
        log.error("系统异常：", e);
        return Result.fail("系统异常：" + e.getMessage());
    }
}