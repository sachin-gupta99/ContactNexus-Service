package com.sachingupta.contactnexus;

import com.jayway.jsonpath.JsonPath;
import com.sachingupta.contactnexus.email.EmailSender;
import com.sachingupta.contactnexus.security.JwtService;
import com.sachingupta.contactnexus.user.AvatarStorage;
import com.sachingupta.contactnexus.user.User;
import com.sachingupta.contactnexus.user.UserRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full application context against a real PostgreSQL 16 (zonky embedded
 * binaries, no Docker), with Flyway running every migration. S3 and email are
 * mocked; everything else, including security filters and rate limits, is real.
 *
 * <p>One database is shared by all test classes, so tests create their own users
 * (unique emails) and never assume an empty table.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                POSTGRES.close();
            } catch (IOException ignored) {
                // shutting down anyway
            }
        }));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("db.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("db.username", () -> "postgres");
        registry.add("db.password", () -> "postgres");
        registry.add("jwt.secret", () -> "integration-test-secret-that-is-at-least-32-bytes-long");
        registry.add("app.email.mode", () -> "console");
        registry.add("app.client-ip.proxy-secret", () -> "integration-test-proxy-secret");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected UserRepository users;

    @Autowired
    protected JwtService jwtService;

    @MockitoBean
    protected AvatarStorage avatarStorage;

    @MockitoBean
    protected EmailSender emailSender;

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** A different client IP per request, so tests don't trip the per-IP rate limits. */
    protected static RequestPostProcessor freshIp() {
        int n = SEQ.incrementAndGet();
        return request -> {
            request.setRemoteAddr("10.%d.%d.%d".formatted((n >> 16) & 255, (n >> 8) & 255, n & 255));
            return request;
        };
    }

    protected static String uniqueEmail(String prefix) {
        return "%s-%d-%d@example.com".formatted(prefix, System.nanoTime() % 1_000_000, SEQ.incrementAndGet());
    }

    /** Signs up through the API, marks the address verified, and returns a bearer token. */
    protected TestUser newUser() throws Exception {
        String email = uniqueEmail("user");
        mvc.perform(post("/api/v1/auth/signup").with(freshIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test User","email":"%s","password":"password123"}""".formatted(email)))
                .andExpect(status().isCreated());
        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        user.setEmailVerified(true);
        users.save(user);
        return new TestUser(user.getId(), email, "Bearer " + jwtService.issueAccessToken(user).token());
    }

    protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected static <T> T read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    public record TestUser(Long id, String email, String bearer) {}
}
