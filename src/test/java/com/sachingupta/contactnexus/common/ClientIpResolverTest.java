package com.sachingupta.contactnexus.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private static final String SECRET = "s3cret-shared-with-vercel";
    private final ClientIpResolver resolver = new ClientIpResolver(SECRET, "CF-Connecting-IP");

    private static MockHttpServletRequest request(String remote) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        return r;
    }

    @Test
    void viaVercelTheLeftMostForwardedValueIsTheVisitor() {
        // Vercel overwrote X-Forwarded-For with the visitor; Render appended Vercel's egress IP.
        MockHttpServletRequest r = request("10.0.0.1");
        r.addHeader(ClientIpResolver.PROXY_SECRET_HEADER, SECRET);
        r.addHeader("X-Forwarded-For", "203.0.113.7, 76.76.21.9");
        r.addHeader("CF-Connecting-IP", "76.76.21.9");
        assertThat(resolver.resolve(r)).isEqualTo("203.0.113.7");
    }

    @Test
    void directCallsCannotForgeTheirIpWithXForwardedFor() {
        MockHttpServletRequest r = request("10.0.0.1");
        r.addHeader("X-Forwarded-For", "1.2.3.4, 198.51.100.20");
        r.addHeader("CF-Connecting-IP", "198.51.100.20");
        assertThat(resolver.resolve(r)).isEqualTo("198.51.100.20");
    }

    @Test
    void aWrongSecretIsTreatedAsADirectCall() {
        MockHttpServletRequest r = request("10.0.0.1");
        r.addHeader(ClientIpResolver.PROXY_SECRET_HEADER, "guess");
        r.addHeader("X-Forwarded-For", "1.2.3.4");
        r.addHeader("CF-Connecting-IP", "198.51.100.20");
        assertThat(resolver.resolve(r)).isEqualTo("198.51.100.20");
    }

    @Test
    void fallsBackToTheSocketAddressWithoutEdgeHeaders() {
        MockHttpServletRequest r = request("127.0.0.1");
        r.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(resolver.resolve(r)).isEqualTo("127.0.0.1");
    }

    @Test
    void rejectsValuesThatAreNotIpAddresses() {
        MockHttpServletRequest r = request("127.0.0.1");
        r.addHeader("CF-Connecting-IP", "<script>alert(1)</script>");
        assertThat(resolver.resolve(r)).isEqualTo("127.0.0.1");

        MockHttpServletRequest viaProxy = request("127.0.0.1");
        viaProxy.addHeader(ClientIpResolver.PROXY_SECRET_HEADER, SECRET);
        viaProxy.addHeader("X-Forwarded-For", "not-an-ip");
        assertThat(resolver.resolve(viaProxy)).isEqualTo("127.0.0.1");
    }

    @Test
    void acceptsIpv6() {
        MockHttpServletRequest r = request("127.0.0.1");
        r.addHeader("CF-Connecting-IP", "2001:db8::1");
        assertThat(resolver.resolve(r)).isEqualTo("2001:db8::1");
    }

    @Test
    void withoutASecretKeepsTheOldBehaviour() {
        ClientIpResolver legacy = new ClientIpResolver("", "CF-Connecting-IP");
        MockHttpServletRequest r = request("10.0.0.1");
        r.addHeader("X-Forwarded-For", "203.0.113.7, 76.76.21.9");
        assertThat(legacy.resolve(r)).isEqualTo("203.0.113.7");
    }
}
