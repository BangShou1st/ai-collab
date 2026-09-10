package com.shitulelv.aicollab.auth.ratelimit;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AuthRateLimiterTest {

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void login_rate_limited_after_burst() {
        AuthRateLimiter limiter = new AuthRateLimiter(mock(StringRedisTemplate.class), 3, 10);
        MockHttpServletRequest request = request("10.0.0.9");

        limiter.checkLogin(request, "Owner");
        limiter.checkLogin(request, "Owner");
        limiter.checkLogin(request, "Owner");

        assertThatThrownBy(() -> limiter.checkLogin(request, "Owner"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_RATE_LIMITED));
    }

    @Test
    void register_rate_limited_by_ip() {
        AuthRateLimiter limiter = new AuthRateLimiter(mock(StringRedisTemplate.class), 30, 2);
        MockHttpServletRequest request = request("10.0.0.10");

        limiter.checkRegister(request);
        limiter.checkRegister(request);

        assertThatThrownBy(() -> limiter.checkRegister(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_RATE_LIMITED));
    }

    @Test
    void spoofed_forwarded_header_does_not_bypass() {
        AuthRateLimiter limiter = new AuthRateLimiter(mock(StringRedisTemplate.class), 1, 10);
        MockHttpServletRequest request = request("10.0.0.11");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        limiter.checkLogin(request, "owner");

        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "9.9.9.9");
        assertThatThrownBy(() -> limiter.checkLogin(request, "owner"))
                .isInstanceOf(BusinessException.class);
    }
}
