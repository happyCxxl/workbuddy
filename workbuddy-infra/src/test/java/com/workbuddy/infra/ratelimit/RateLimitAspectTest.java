package com.workbuddy.infra.ratelimit;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.annotation.RateLimit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流切面测试。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {

    @Mock
    private BucketRateLimiter bucketRateLimiter;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    @InjectMocks
    private RateLimitAspect rateLimitAspect;

    @BeforeEach
    void stubRequestContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldRejectWhenBucketExhausted() throws Throwable {
        RateLimit annotation = sampleAnnotation();
        stubJoinPoint();

        when(bucketRateLimiter.tryAcquire(anyString(), eq(2), eq(30))).thenReturn(false);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> rateLimitAspect.around(joinPoint, annotation));

        assertEquals(ErrorCode.RATE_LIMITED, e.getErrorCode());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void shouldProceedWhenTokenAcquired() throws Throwable {
        RateLimit annotation = sampleAnnotation();
        stubJoinPoint();

        when(bucketRateLimiter.tryAcquire(anyString(), eq(2), eq(30))).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", rateLimitAspect.around(joinPoint, annotation));
    }

    /** 取真实注解实例（参数即声明值），不依赖 Mockito 对注解的支持 */
    @RateLimit(capacity = 2, periodSeconds = 30)
    void annotatedSample() {
    }

    private RateLimit sampleAnnotation() throws NoSuchMethodException {
        return RateLimitAspectTest.class.getDeclaredMethod("annotatedSample").getAnnotation(RateLimit.class);
    }

    private void stubJoinPoint() {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn(RateLimitAspectTest.class);
        when(signature.getName()).thenReturn("annotatedSample");
    }
}
