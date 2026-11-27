package com.sachingupta.contactnexus.user;

import com.sachingupta.contactnexus.common.NotFoundException;
import com.sachingupta.contactnexus.user.dto.CardShareResponse;
import com.sachingupta.contactnexus.user.dto.PublicCardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A public, read-only link to the user's own contact card. The random token is
 * the whole capability, so it can be rotated (old links stop working) or the
 * share removed at any time. Only the fields the owner picked are ever returned.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CardShareService {

    /** What a card can show besides the name, which is always shown. */
    public enum Field { EMAIL, PHONE, WORK, ADDRESS, SOCIAL, BIO, PHOTO }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 24;
    private static final List<String> AVAILABLE = Arrays.stream(Field.values()).map(Enum::name).toList();

    private final CardShareRepository shares;
    private final UserRepository users;
    private final AvatarStorage avatarStorage;

    @Transactional(readOnly = true)
    public CardShareResponse get(Long userId) {
        return shares.findById(userId)
                .map(s -> new CardShareResponse(true, s.getToken(), List.of(s.getFields().split(",")).stream()
                        .filter(f -> !f.isBlank()).toList(), AVAILABLE))
                .orElse(new CardShareResponse(false, null, List.of(Field.EMAIL.name(), Field.WORK.name()), AVAILABLE));
    }

    /** Turns sharing on (creating a token) or updates which fields are shown. */
    public CardShareResponse save(Long userId, List<String> requested) {
        String fields = parse(requested).stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        CardShare share = shares.findById(userId).orElseGet(() ->
                CardShare.builder().userId(userId).token(newToken()).build());
        share.setFields(fields);
        shares.save(share);
        return get(userId);
    }

    /** New token: every link handed out so far stops working. */
    public CardShareResponse rotate(Long userId) {
        CardShare share = shares.findById(userId)
                .orElseThrow(() -> new NotFoundException("Card sharing is off"));
        share.setToken(newToken());
        return get(userId);
    }

    public void disable(Long userId) {
        shares.deleteById(userId);
    }

    @Transactional(readOnly = true)
    public PublicCardResponse publicCard(String token) {
        CardShare share = token == null || token.length() > 64 ? null : shares.findByToken(token).orElse(null);
        if (share == null) {
            throw new NotFoundException("This card link is invalid or was turned off");
        }
        User u = users.findById(share.getUserId())
                .orElseThrow(() -> new NotFoundException("This card link is invalid or was turned off"));
        Set<Field> f = parse(List.of(share.getFields().split(",")));
        Profile p = u.getProfile() == null ? new Profile() : u.getProfile();
        boolean addr = f.contains(Field.ADDRESS), social = f.contains(Field.SOCIAL), bio = f.contains(Field.BIO);
        String photo = !f.contains(Field.PHOTO) ? null
                : p.getImageKey() != null ? avatarStorage.presignedUrl(p.getImageKey()) : p.getImageUrl();
        return new PublicCardResponse(
                u.getName(),
                f.contains(Field.EMAIL) ? u.getEmail() : null,
                f.contains(Field.PHONE) ? p.getPhone() : null,
                f.contains(Field.WORK) ? p.getWork() : null,
                addr ? p.getStreet() : null,
                addr ? p.getArea() : null,
                addr ? p.getCity() : null,
                addr ? p.getState() : null,
                addr ? p.getPincode() : null,
                social ? p.getGithub() : null,
                social ? p.getLinkedin() : null,
                social ? p.getInstagram() : null,
                bio ? p.getBioHeading() : null,
                bio ? p.getBioDescription() : null,
                photo);
    }

    private static Set<Field> parse(List<String> raw) {
        Set<Field> out = EnumSet.noneOf(Field.class);
        for (String r : raw) {
            if (r == null || r.isBlank()) continue;
            try {
                out.add(Field.valueOf(r.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown card field '%s'; allowed: %s".formatted(r, AVAILABLE));
            }
        }
        return out;
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
