package com.sachingupta.contactnexus.user;

import com.sachingupta.contactnexus.security.AuthUser;
import com.sachingupta.contactnexus.security.PasswordResetService;
import com.sachingupta.contactnexus.security.RefreshCookie;
import com.sachingupta.contactnexus.security.RefreshTokenService;
import com.sachingupta.contactnexus.user.UserService.AuthResult;
import com.sachingupta.contactnexus.user.dto.AuthResponse;
import com.sachingupta.contactnexus.user.dto.ChangePasswordRequest;
import com.sachingupta.contactnexus.user.dto.ForgotPasswordRequest;
import com.sachingupta.contactnexus.user.dto.LoginRequest;
import com.sachingupta.contactnexus.user.dto.ResendVerificationRequest;
import com.sachingupta.contactnexus.user.dto.ResetPasswordRequest;
import com.sachingupta.contactnexus.user.dto.SessionResponse;
import com.sachingupta.contactnexus.user.dto.VerifyEmailRequest;
import com.sachingupta.contactnexus.user.dto.SignupRequest;
import com.sachingupta.contactnexus.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService service;
    private final RefreshCookie refreshCookie;
    private final PasswordResetService passwordResetService;
    private final RefreshTokenService refreshTokenService;

    /**
     * No refresh cookie: the account isn't verified yet. The short-lived access
     * token only lets the client finish profile setup (avatar upload).
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signup(@Valid @RequestBody SignupRequest body) {
        return service.signup(body);
    }

    /** The link in the confirmation email lands here (via the UI); signs the user in. */
    @PostMapping("/verify-email")
    public AuthResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest body,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        return writeResult(service.verifyEmail(body.token(), request), response);
    }

    /** Enumeration-safe: always 200, sends only if the address has an unverified account. */
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.OK)
    public void resendVerification(@Valid @RequestBody ResendVerificationRequest body) {
        service.resendVerification(body.email());
    }

    /** Lives under /auth so the path-scoped refresh cookie identifies "this device". */
    @GetMapping("/sessions")
    public List<SessionResponse> sessions(@AuthenticationPrincipal AuthUser principal,
                                          HttpServletRequest request) {
        return refreshTokenService.listSessions(principal.getId(), refreshCookie.read(request));
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@AuthenticationPrincipal AuthUser principal,
                              @PathVariable UUID id,
                              HttpServletRequest request,
                              HttpServletResponse response) {
        if (refreshTokenService.revokeSession(principal.getId(), id, refreshCookie.read(request))) {
            refreshCookie.clear(response);
        }
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest body,
                              HttpServletRequest request,
                              HttpServletResponse response) {
        return writeResult(service.login(body, request), response);
    }

    /**
     * Rotates the refresh token on the cookie and returns a fresh access token.
     * The old cookie value is marked revoked server-side and replaced with a new
     * one in the same family; a replay of the old value trips reuse detection.
     */
    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String raw = refreshCookie.read(request);
        return writeResult(service.refresh(raw, request), response);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String raw = refreshCookie.read(request);
        service.logout(raw);
        refreshCookie.clear(response);
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthUser principal) {
        return service.getById(principal.getId());
    }

    /**
     * Enumeration-safe: always returns 200 whether or not the email is registered,
     * so an attacker can't probe which accounts exist. The service silently
     * no-ops when the email is unknown.
     */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.OK)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest body,
                               HttpServletRequest request) {
        passwordResetService.requestReset(body.email(), request);
    }

    /**
     * Completes a password reset. On success the user's refresh-token families
     * are all revoked - any session an attacker might be riding dies here, and
     * the user has to sign in again with the new password.
     */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest body) {
        passwordResetService.completeReset(body.token(), body.newPassword());
    }

    /**
     * Signs out every other device; this one gets a fresh cookie and access token
     * so the user isn't bounced to the sign-in page mid-action.
     */
    @PostMapping("/change-password")
    public AuthResponse changePassword(@AuthenticationPrincipal AuthUser principal,
                                       @Valid @RequestBody ChangePasswordRequest body,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        return writeResult(service.changePassword(principal.getId(), body, request), response);
    }

    /** Revokes every refresh token for the user, including this device's. */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal AuthUser principal, HttpServletResponse response) {
        service.logoutEverywhere(principal.getId());
        refreshCookie.clear(response);
    }

    private AuthResponse writeResult(AuthResult result, HttpServletResponse response) {
        refreshCookie.write(response, result.refreshToken(), result.refreshTtl());
        return result.body();
    }
}
