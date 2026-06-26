package com.linrun.interview.common.aspect;

import com.linrun.interview.common.annotation.DistributeLock;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 分布式锁 AOP 切面（基于 Redisson RLock，对齐 know-engine DistributeLockAspect）。
 *
 * <p>解析 {@link DistributeLock#key()} 的 SpEL 表达式生成最终 lockKey，
 * 获取锁失败抛 {@link BusinessException}(BAD_REQUEST) 由全局异常处理统一返回。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class DistributeLockAspect {

    private static final String LOCK_PREFIX = "distlock:";

    private final RedissonClient redissonClient;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(com.linrun.interview.common.annotation.DistributeLock)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        DistributeLock distributeLock = method.getAnnotation(DistributeLock.class);
        String lockKey = LOCK_PREFIX + resolveKey(distributeLock, method, joinPoint.getArgs());

        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(
                distributeLock.waitTime(), distributeLock.leaseTime(), distributeLock.unit());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取分布式锁被中断: " + lockKey, e);
        }
        if (!acquired) {
            log.warn("获取分布式锁失败: lockKey={}, method={}", lockKey, method.getName());
            throw new BusinessException(ErrorCode.BAD_REQUEST, distributeLock.message());
        }
        try {
            return joinPoint.proceed();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 解析锁 key：注解 key 为空时用「类名#方法名」全局互斥；
     * 否则按 SpEL 表达式求值（可引用方法参数，如 #docId）。
     */
    private String resolveKey(DistributeLock annotation, Method method, Object[] args) {
        String expression = annotation.key();
        if (expression == null || expression.isBlank()) {
            return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        }
        EvaluationContext context = createContext(method, args);
        try {
            Expression exp = parser.parseExpression(expression);
            Object value = exp.getValue(context);
            return value == null ? expression : String.valueOf(value);
        } catch (Exception e) {
            log.warn("解析分布式锁 key SpEL 失败，回退原表达式: expr={}, error={}", expression, e.getMessage());
            return expression;
        }
    }

    private EvaluationContext createContext(Method method, Object[] args) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        String[] paramNames = discoverer.getParameterNames(method);
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        return context;
    }
}
