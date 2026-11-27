package com.sachingupta.contactnexus.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final RateLimitFilter filter =
            new RateLimitFilter(new com.sachingupta.contactnexus.common.ClientIpResolver("secret", "CF-Connecting-IP"));

    private int send(String method, String uri, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void passwordRecheckEndpointsShareOneBucketPerIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(send("POST", "/api/v1/auth/change-password", "1.1.1.1")).isEqualTo(200);
            assertThat(send("DELETE", "/api/v1/users/me", "1.1.1.1")).isEqualTo(200);
        }
        assertThat(send("POST", "/api/v1/auth/change-password", "1.1.1.1")).isEqualTo(429);
        assertThat(send("DELETE", "/api/v1/users/me", "1.1.1.1")).isEqualTo(429);

        // Other clients and other methods on the same path are unaffected.
        assertThat(send("DELETE", "/api/v1/users/me", "2.2.2.2")).isEqualTo(200);
        assertThat(send("PUT", "/api/v1/users/me", "1.1.1.1")).isEqualTo(200);
        assertThat(send("GET", "/api/v1/users/me", "1.1.1.1")).isEqualTo(200);
    }

    @Test
    void unlistedEndpointsAreNotLimited() throws Exception {
        for (int i = 0; i < 50; i++) {
            assertThat(send("POST", "/api/v1/contacts", "3.3.3.3")).isEqualTo(200);
        }
    }

    @Test
    void loginStillLimitedAtTenPerWindow() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(send("POST", "/api/v1/auth/login", "4.4.4.4")).isEqualTo(200);
        }
        assertThat(send("POST", "/api/v1/auth/login", "4.4.4.4")).isEqualTo(429);
    }

    @Test
    void aForgedXForwardedForDoesNotGetAFreshBucket() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("5.5.5.5");
            request.addHeader("CF-Connecting-IP", "5.5.5.5");
            request.addHeader("X-Forwarded-For", "9.9.9." + i); // a different fake IP every time
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("5.5.5.5");
        request.addHeader("CF-Connecting-IP", "5.5.5.5");
        request.addHeader("X-Forwarded-For", "9.9.9.99");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(429);
    }
}
