package com.sachingupta.contactnexus.user;

import com.sachingupta.contactnexus.security.AuthUser;
import com.sachingupta.contactnexus.security.RefreshCookie;
import com.sachingupta.contactnexus.user.dto.DeleteAccountRequest;
import com.sachingupta.contactnexus.user.dto.UpdateProfileRequest;
import com.sachingupta.contactnexus.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService service;
    private final RefreshCookie refreshCookie;

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthUser principal) {
        return service.getById(principal.getId());
    }

    @PutMapping("/me")
    public UserResponse updateMe(@AuthenticationPrincipal AuthUser principal,
                                 @Valid @RequestBody UpdateProfileRequest body) {
        return service.updateProfile(principal.getId(), body);
    }

    @PutMapping(path = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> uploadAvatar(@AuthenticationPrincipal AuthUser principal,
                                            @RequestParam("file") MultipartFile file) {
        return Map.of("imageUrl", service.updateAvatar(principal.getId(), file));
    }

    /** Permanently deletes the account and everything it owns. Requires the password. */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal AuthUser principal,
                         @Valid @RequestBody DeleteAccountRequest body,
                         HttpServletResponse response) {
        service.deleteAccount(principal.getId(), body);
        refreshCookie.clear(response);
    }
}
