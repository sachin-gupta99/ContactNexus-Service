package com.sachingupta.contactnexus.user;

import com.sachingupta.contactnexus.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowIT extends IntegrationTest {

    private static final Pattern VERIFY_LINK = Pattern.compile("mode=verify&token=([A-Za-z0-9_%-]+)");

    private String lastVerificationToken(String email) {
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce()).send(eq(email), anyString(), html.capture());
        Matcher m = VERIFY_LINK.matcher(html.getValue());
        assertThat(m.find()).as("verification link in email").isTrue();
        return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(json(post("/api/v1/auth/login"), """
                {"email":"%s","password":"%s"}""".formatted(email, password)).with(freshIp())).andReturn();
    }

    @Test
    void signupRequiresVerificationAndTheLinkSignsYouIn() throws Exception {
        String email = uniqueEmail("verify");
        MvcResult signup = mvc.perform(json(post("/api/v1/auth/signup"), """
                        {"name":"Vee","email":"%s","password":"password123"}""".formatted(email)).with(freshIp()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.emailVerified").value(false))
                .andReturn();
        assertThat(signup.getResponse().getCookie("cn_refresh")).as("no session cookie before verifying").isNull();

        MvcResult unverified = login(email, "password123");
        assertThat(unverified.getResponse().getStatus()).isEqualTo(403);
        assertThat(unverified.getResponse().getContentAsString()).contains("EMAIL_NOT_VERIFIED");
        // A wrong password must not reveal that the account exists but is unverified.
        assertThat(login(email, "wrong-password").getResponse().getStatus()).isEqualTo(401);

        String token = lastVerificationToken(email);
        MvcResult verified = mvc.perform(json(post("/api/v1/auth/verify-email"), "{\"token\":\"%s\"}".formatted(token)).with(freshIp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.emailVerified").value(true))
                .andReturn();
        assertThat(verified.getResponse().getCookie("cn_refresh")).isNotNull();
        mvc.perform(json(post("/api/v1/auth/verify-email"), "{\"token\":\"%s\"}".formatted(token)).with(freshIp()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void replayingARotatedRefreshTokenRevokesTheWholeFamily() throws Exception {
        TestUser u = newUser();
        Cookie first = login(u.email(), "password123").getResponse().getCookie("cn_refresh");

        MvcResult rotated = mvc.perform(post("/api/v1/auth/refresh").cookie(first).with(freshIp()))
                .andExpect(status().isOk()).andReturn();
        Cookie second = rotated.getResponse().getCookie("cn_refresh");

        // The stolen (old) token is replayed: 401, and the legitimate new one dies too.
        mvc.perform(post("/api/v1/auth/refresh").cookie(first).with(freshIp())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(second).with(freshIp())).andExpect(status().isUnauthorized());
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenFromDifferentIps() throws Exception {
        TestUser u = newUser();
        for (int i = 1; i <= 4; i++) {
            assertThat(login(u.email(), "nope-" + i).getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult fifth = login(u.email(), "nope-5");
        assertThat(fifth.getResponse().getStatus()).isEqualTo(429);
        assertThat(fifth.getResponse().getContentAsString()).contains("ACCOUNT_LOCKED");
        assertThat(login(u.email(), "password123").getResponse().getStatus()).as("right password while locked").isEqualTo(429);
    }

    @Test
    void sessionsListMarksThisDeviceAndCantRevokeOtherUsers() throws Exception {
        TestUser u = newUser();
        Cookie mine = login(u.email(), "password123").getResponse().getCookie("cn_refresh");
        login(u.email(), "password123"); // a second device

        MvcResult list = mvc.perform(get("/api/v1/auth/sessions").cookie(mine).header("Authorization", u.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();
        java.util.List<Boolean> current = read(list, "$[*].current");
        assertThat(current).containsExactlyInAnyOrder(true, false);
        String myFamily = read(list, "$[?(@.current == true)].id[0]") instanceof String s ? s
                : ((java.util.List<String>) read(list, "$[?(@.current == true)].id")).get(0);

        TestUser stranger = newUser();
        mvc.perform(delete("/api/v1/auth/sessions/" + myFamily).header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/auth/refresh").cookie(mine).with(freshIp())).andExpect(status().isOk());
    }
}
