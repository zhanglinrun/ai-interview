package com.linrun.interview.common.annotation;

import com.linrun.interview.infra.aspect.DistributeLockAspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解（基于 Redisson RLock，对齐业界实践 @DistributeLock）。
 *
 * <p>用于方法级别的分布式互斥控制，防止同一资源的并发写操作产生脏数据/重复向量化。
 * key 支持 SpEL 表达式读取方法参数（如 {@code key = "#docId"}），不填则按
 * 「类名#方法名」全局互斥。
 *
 * <p>示例：
 * <pre>
 * &#64;DistributeLock(key = "'kb:split:' + #docId", waitTime = 0, leaseTime = 120)
 * public int split(Long docId) { ... }
 * </pre>
 *
 * @see DistributeLockAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributeLock {

    /**
     * 锁 key，支持 SpEL 表达式。不填时使用「类名#方法名」作为 key。
     *
     * @return 锁 key 表达式
     */
    String key() default "";

    /**
     * 获取锁等待时间，0 表示不等待（拿不到立即失败）。
     *
     * @return 等待时间
     */
    long waitTime() default 0;

    /**
     * 持有锁最长时间；小于等于 0 时使用 Redisson watchdog 自动续期。
     *
     * @return 持锁时间
     */
    long leaseTime() default 120;

    /**
     * 时间单位，默认秒。
     *
     * @return 时间单位
     */
    TimeUnit unit() default TimeUnit.SECONDS;

    /**
     * 获取锁失败时的提示消息。
     *
     * @return 提示消息
     */
    String message() default "操作正在处理中，请稍后再试";
}
