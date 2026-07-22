package com.shitulelv.aicollab.common.security;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRequestOriginValidatorTest {

    private AuthRequestOriginValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AuthRequestOriginValidator(new CorsProperties(List.of(
                "http://localhost",
                "http://localhost:5173",
                "https://app.example.test")));
    }

    @Test
    void acceptsAnExactlyAllowedOrigin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "http://localhost:5173");

        validator.validate(request);
    }

    @Test
    void rejectsOriginPrefixAndSuffixTricks() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "http://localhost:5173.evil.test");

        assertForbidden(request);
    }

    @Test
    void derivesAndAcceptsTheExactOriginFromRefererWhenOriginIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Referer", "https://app.example.test/auth/login?next=%2Fprojects");

        validator.validate(request);
    }

    @Test
    void rejectsMalformedAndUntrustedReferer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Referer", "https://app.example.test.evil.invalid/auth/login");

        assertForbidden(request);
    }

    @Test
    void originTakesPriorityOverReferer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://evil.invalid");
        request.addHeader("Referer", "https://app.example.test/auth/login");

        assertForbidden(request);
    }

    @Test
    void rejectsMultipleAndCommaCombinedOriginValues() {
        MockHttpServletRequest repeated = new MockHttpServletRequest();
        repeated.addHeader("Origin", "http://localhost:5173");
        repeated.addHeader("Origin", "https://app.example.test");
        assertForbidden(repeated);

        MockHttpServletRequest combined = new MockHttpServletRequest();
        combined.addHeader("Origin", "http://localhost:5173, https://app.example.test");
        assertForbidden(combined);
    }

    @Test
    void rejectsMultipleAndCommaCombinedRefererValues() {
        MockHttpServletRequest repeated = new MockHttpServletRequest();
        repeated.addHeader("Referer", "http://localhost:5173/login");
        repeated.addHeader("Referer", "https://app.example.test/login");
        assertForbidden(repeated);

        MockHttpServletRequest combined = new MockHttpServletRequest();
        combined.addHeader("Referer", "http://localhost:5173/login, https://app.example.test/login");
        assertForbidden(combined);
    }

    @Test
    void rejectsPresentButEmptyOriginAndRefererValues() {
        MockHttpServletRequest emptyOrigin = new MockHttpServletRequest();
        emptyOrigin.addHeader("Origin", "");
        emptyOrigin.addHeader("Referer", "https://app.example.test/login");
        assertForbidden(emptyOrigin);

        MockHttpServletRequest emptyReferer = new MockHttpServletRequest();
        emptyReferer.addHeader("Referer", " ");
        assertForbidden(emptyReferer);
    }

    @Test
    void normalizesRefererSchemeHostAndDefaultPortBeforeExactMatching() {
        MockHttpServletRequest httpsRequest = new MockHttpServletRequest();
        httpsRequest.addHeader("Referer", "HTTPS://APP.EXAMPLE.TEST:443/auth/login");
        validator.validate(httpsRequest);

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("Referer", "HTTP://LOCALHOST:80/auth/login");
        validator.validate(httpRequest);
    }

    @Test
    void rejectsRequestsWithoutOriginOrReferer() {
        assertForbidden(new MockHttpServletRequest());
    }

    private void assertForbidden(MockHttpServletRequest request) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_FORBIDDEN))
                .hasMessage(ErrorCode.AUTH_FORBIDDEN.message());
    }
}
