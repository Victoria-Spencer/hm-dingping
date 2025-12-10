package com.hmdp.lock.aop;

import com.hmdp.lock.core.DatabaseDLock;
import com.hmdp.lock.core.DLock;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;

@Aspect
@Component
@Slf4j
public class LockThreadLocalCleanAspect {

    @Pointcut("execution(* com.hmdp.lock.core.DLock+.lock(..)) || " +
            "execution(* com.hmdp.lock.core.DLock+.tryLock(..))")
    public void lockOperationPointcut() {}

    @Around("lockOperationPointcut() && this(lock)")
    public Object aroundLockOperation(ProceedingJoinPoint joinPoint, DLock lock) throws Throwable {
        if (!(lock instanceof DatabaseDLock)) {
            return joinPoint.proceed();
        }

        DatabaseDLock databaseDLock = (DatabaseDLock) lock;
        try {
            return joinPoint.proceed();
        } catch (Exception e) {
            log.error("锁操作异常", e);
            throw e;
        } finally {
            try {
                Field countField = DatabaseDLock.class.getDeclaredField("reentrantCountThreadLocal");
                countField.setAccessible(true);
                ThreadLocal<Integer> countThreadLocal = (ThreadLocal<Integer>) countField.get(databaseDLock);
                Integer count = countThreadLocal.get();

                if (count == null || count <= 0) {
                    databaseDLock.forceCleanThreadLocal();
                }
            } catch (Exception e) {
                log.warn("清理ThreadLocal反射操作失败", e);
                databaseDLock.forceCleanThreadLocal();
            }
        }
    }

    @Around("execution(* java.util.concurrent.ExecutorService+.execute(..)) && args(runnable)")
    public void aroundExecutorExecute(ProceedingJoinPoint joinPoint, Runnable runnable) throws Throwable {
        Runnable wrappedRunnable = () -> {
            try {
                runnable.run();
            } finally {
                cleanAllLockThreadLocal();
            }
        };
        joinPoint.proceed(new Object[]{wrappedRunnable});
    }

    private void cleanAllLockThreadLocal() {
        try {
            Field threadLocalsField = Thread.class.getDeclaredField("threadLocals");
            threadLocalsField.setAccessible(true);
            Object threadLocalMap = threadLocalsField.get(Thread.currentThread());
            if (threadLocalMap == null) return;

            Class<?> threadLocalMapClass = threadLocalMap.getClass();
            Field tableField = threadLocalMapClass.getDeclaredField("table");
            tableField.setAccessible(true);
            Object[] table = (Object[]) tableField.get(threadLocalMap);

            if (table != null) {
                for (Object entry : table) {
                    if (entry == null) continue;

                    Field valueField = entry.getClass().getDeclaredField("value");
                    valueField.setAccessible(true);
                    Object value = valueField.get(entry);

                    if (value instanceof String && ((String) value).contains(":")) {
                        Field threadLocalField = entry.getClass().getDeclaredField("referent");
                        threadLocalField.setAccessible(true);
                        ThreadLocal<?> threadLocal = (ThreadLocal<?>) threadLocalField.get(entry);
                        threadLocal.remove();
                    } else if (value instanceof Integer) {
                        Field threadLocalField = entry.getClass().getDeclaredField("referent");
                        threadLocalField.setAccessible(true);
                        ThreadLocal<?> threadLocal = (ThreadLocal<?>) threadLocalField.get(entry);
                        threadLocal.remove();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("清理线程池ThreadLocal失败", e);
        }
    }
}