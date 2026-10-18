package com.workbuddy.infra.ratelimit;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.annotation.RateLimit;
import com.workbuddy.common.utils.ClientIpUtil;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 限流切面：拦截标注 {@link RateLimit} 的方法，按客户端 IP 与接口名各自计数。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    /** 桶键前缀 */
    private static final String KEY_PREFIX = "knowledge:ratelimit:";

    private final BucketRateLimiter bucketRateLimiter;

    /**
     * 限流切面：按「客户端 IP + 接口」取桶，取不到令牌即拒绝。
     *
     * @param joinPoint 被拦截的方法
     * @param rateLimit 方法上的限流配置
     * @return 原方法的返回值
     * @throws Throwable 原方法抛出的异常；无令牌时抛限流错误
     */
    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = bucketKey(joinPoint);
        if (!bucketRateLimiter.tryAcquire(key, rateLimit.capacity(), rateLimit.periodSeconds())) {
            throw new KnowledgeException(ErrorCode.RATE_LIMITED, messageOf(rateLimit));
        }
        return joinPoint.proceed();
    }

    /** 桶键：客户端 IP + 接口名，不同接口各自独立计数 */
    private String bucketKey(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return KEY_PREFIX + ClientIpUtil.getClientIp() + ":"
                + signature.getDeclaringType().getSimpleName() + "." + signature.getName();
    }

    private String messageOf(RateLimit rateLimit) {
        return StringUtils.hasText(rateLimit.message())
                ? rateLimit.message() : ErrorCode.RATE_LIMITED.getMessage();
    }
}
