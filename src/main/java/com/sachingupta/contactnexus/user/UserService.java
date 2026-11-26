package com.sachingupta.contactnexus.user;

import com.sachingupta.contactnexus.common.ConflictException;
import com.sachingupta.contactnexus.common.NotFoundException;
import com.sachingupta.contactnexus.contact.ContactRepository;
import com.sachingupta.contactnexus.security.PasswordResetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.sachingupta.contactnexus.security.JwtService;
import com.sachingupta.contactnexus.security.JwtService.IssuedAccessToken;
import com.sachingupta.contactnexus.security.RefreshTokenService;
import com.sachingupta.contactnexus.security.RefreshTokenService.IssuedRefreshToken;
import com.sachingupta.contactnexus.security.RefreshTokenService.RotationResult;
import com.sachingupta.contactnexus.user.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetService passwordResetService;
    private final ContactRepository contactRepository;
    private final AvatarStorage avatarStorage;

    public AuthResult signup(SignupRequest req, HttpServletRequest request) {
        String email = normalize(req.email());
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with email %s already exists".formatted(email));
        }

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(req.password()))
                .name(req.name())
                .build();

        Profile profile = toProfile(req.profile());
        profile.setUser(user);
        user.setProfile(profile);

        User saved = users.save(user);
        return issueFor(saved, request);
    }

    public AuthResult login(LoginRequest req, HttpServletRequest request) {
        // Same message either way so the response cannot be used to enumerate accounts.
        User user = users.findByEmailIgnoreCase(normalize(req.email()))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        return issueFor(user, request);
    }

    /**
     * {@code noRollbackFor} must be here, not just on {@code RefreshTokenService.rotate()}:
     * this method's {@code @Transactional} (inherited from the class) opens the outer
     * transaction, so its rollback rules are the ones Spring consults when
     * {@code rotate()} throws. Without this, the reuse-detection {@code revokeFamily}
     * write inside {@code rotate()} would be rolled back and the attacker's rotated
     * token would stay valid.
     */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public AuthResult refresh(String rawRefreshToken, HttpServletRequest request) {
        RotationResult rotation = refreshTokenService.rotate(rawRefreshToken, request);
        IssuedAccessToken access = jwtService.issueAccessToken(rotation.user());
        AuthResponse body = new AuthResponse(
                access.token(),
                access.ttl().getSeconds(),
                toResponse(rotation.user()));
        return new AuthResult(body, rotation.refreshToken().rawToken(), rotation.refreshToken().ttl());
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    @Transactional(readOnly = true)
    public UserResponse getById(Long id) {
        return toResponse(requireById(id));
    }

    /**
     * Replaces the name and every editable profile field. The avatar is left alone;
     * it changes only through {@link #updateAvatar}.
     */
    public UserResponse updateProfile(Long userId, UpdateProfileRequest req) {
        User user = requireById(userId);
        user.setName(req.name().trim());

        if (req.profile() != null) {
            Profile profile = user.getProfile();
            if (profile == null) {
                profile = new Profile();
                profile.setUser(user);
                user.setProfile(profile);
            }
            applyDetails(profile, req.profile());
        }
        return toResponse(user);
    }

    /**
     * Revokes every session, including this one, then issues fresh tokens so the
     * caller stays signed in while every other device is signed out.
     */
    public AuthResult changePassword(Long userId, ChangePasswordRequest req, HttpServletRequest request) {
        User user = requireById(userId);
        requirePassword(user, req.currentPassword(), "Current password is incorrect");
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        refreshTokenService.revokeAllForUser(userId);
        passwordResetService.sendPasswordChangedEmail(user);
        return issueFor(user, request);
    }

    public void logoutEverywhere(Long userId) {
        refreshTokenService.revokeAllForUser(userId);
    }

    /**
     * Removes the user and everything they own. Contacts and tokens are deleted
     * explicitly (contacts.owner_id has no foreign key; the token tables reference
     * users without cascade), and the avatar object is removed after commit.
     */
    public void deleteAccount(Long userId, DeleteAccountRequest req) {
        User user = requireById(userId);
        requirePassword(user, req.password(), "Password is incorrect");

        String avatarKey = user.getProfile() == null ? null : user.getProfile().getImageKey();
        contactRepository.deleteByOwnerId(userId);
        refreshTokenService.deleteAllForUser(userId);
        passwordResetService.deleteAllForUser(userId);
        users.delete(user);

        if (avatarKey != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    avatarStorage.delete(avatarKey);
                }
            });
        }
    }

    // 400 rather than 401: the session is valid, only the typed password is wrong,
    // and a 401 would make the UI try to refresh the session.
    private void requirePassword(User user, String rawPassword, String message) {
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    /**
     * @return a presigned URL for the newly stored avatar
     */
    public String updateAvatar(Long userId, MultipartFile file) {
        User user = requireById(userId);
        String key = avatarStorage.store(userId, file);

        Profile profile = user.getProfile();
        if (profile == null) {
            profile = new Profile();
            profile.setUser(user);
            user.setProfile(profile);
        }
        String previousKey = profile.getImageKey();
        profile.setImageKey(key);
        profile.setImageUrl(null);

        // Delete the replaced object only once the new key is committed, so a rolled
        // back transaction never leaves the profile pointing at a deleted object.
        if (previousKey != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    avatarStorage.delete(previousKey);
                }
            });
        }

        return avatarStorage.presignedUrl(key);
    }

    private UserResponse toResponse(User user) {
        return UserResponse.from(user, avatarUrlOf(user.getProfile()));
    }

    private String avatarUrlOf(Profile profile) {
        if (profile == null) {
            return null;
        }
        if (profile.getImageKey() != null) {
            return avatarStorage.presignedUrl(profile.getImageKey());
        }
        return profile.getImageUrl();
    }

    private AuthResult issueFor(User user, HttpServletRequest request) {
        IssuedAccessToken access = jwtService.issueAccessToken(user);
        IssuedRefreshToken refresh = refreshTokenService.issueFor(user, request);
        AuthResponse body = new AuthResponse(
                access.token(),
                access.ttl().getSeconds(),
                toResponse(user));
        return new AuthResult(body, refresh.rawToken(), refresh.ttl());
    }

    private User requireById(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new NotFoundException("User %d not found".formatted(id)));
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private Profile toProfile(ProfileDto dto) {
        Profile profile = new Profile();
        if (dto == null) {
            return profile;
        }
        applyDetails(profile, dto);
        profile.setImageUrl(clean(dto.imageUrl()));
        return profile;
    }

    /** Copies every text field except the image, which has its own update path. */
    private void applyDetails(Profile profile, ProfileDto dto) {
        profile.setWork(clean(dto.work()));
        profile.setPhone(clean(dto.phone()));
        profile.setStreet(clean(dto.street()));
        profile.setArea(clean(dto.area()));
        profile.setCity(clean(dto.city()));
        profile.setState(clean(dto.state()));
        profile.setPincode(clean(dto.pincode()));
        profile.setGithub(clean(dto.github()));
        profile.setLinkedin(clean(dto.linkedin()));
        profile.setInstagram(clean(dto.instagram()));
        profile.setLikes(clean(dto.likes()));
        profile.setMovie(clean(dto.movie()));
        profile.setInterests(clean(dto.interests()));
        profile.setBioHeading(clean(dto.bioHeading()));
        profile.setBioDescription(clean(dto.bioDescription()));
    }

    /** Trims, and stores blank as null so "cleared" and "never set" look the same. */
    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Carries the client-facing body plus the raw refresh token the controller
     * needs to write into the HttpOnly cookie. The service does not touch the
     * response directly so it stays easy to test.
     */
    public record AuthResult(AuthResponse body, String refreshToken, Duration refreshTtl) {}
}
