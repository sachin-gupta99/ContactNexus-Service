package com.sachingupta.contactnexus.user;

import com.sachingupta.contactnexus.common.ConflictException;
import com.sachingupta.contactnexus.common.NotFoundException;
import com.sachingupta.contactnexus.security.JwtService;
import com.sachingupta.contactnexus.user.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AvatarStorage avatarStorage;

    public AuthResponse signup(SignupRequest req) {
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
        return new AuthResponse(jwtService.issue(saved), UserResponse.from(saved));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        // Same message either way so the response cannot be used to enumerate accounts.
        User user = users.findByEmailIgnoreCase(normalize(req.email()))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        return new AuthResponse(jwtService.issue(user), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public UserResponse getById(Long id) {
        return UserResponse.from(requireById(id));
    }

    public String updateAvatar(Long userId, MultipartFile file) {
        User user = requireById(userId);
        String imageUrl = avatarStorage.store(userId, file);

        Profile profile = user.getProfile();
        if (profile == null) {
            profile = new Profile();
            profile.setUser(user);
            user.setProfile(profile);
        }
        profile.setImageUrl(imageUrl);

        return imageUrl;
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
        profile.setWork(dto.work());
        profile.setPhone(dto.phone());
        profile.setStreet(dto.street());
        profile.setArea(dto.area());
        profile.setCity(dto.city());
        profile.setState(dto.state());
        profile.setPincode(dto.pincode());
        profile.setGithub(dto.github());
        profile.setLinkedin(dto.linkedin());
        profile.setInstagram(dto.instagram());
        profile.setLikes(dto.likes());
        profile.setMovie(dto.movie());
        profile.setInterests(dto.interests());
        profile.setBioHeading(dto.bioHeading());
        profile.setBioDescription(dto.bioDescription());
        profile.setImageUrl(dto.imageUrl());
        return profile;
    }
}
