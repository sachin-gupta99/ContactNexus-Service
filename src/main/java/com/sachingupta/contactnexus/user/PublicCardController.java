package com.sachingupta.contactnexus.user;

import com.sachingupta.contactnexus.user.dto.PublicCardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** No authentication: the unguessable token in the link is the permission. Rate limited per IP. */
@RestController
@RequestMapping("/api/v1/public/cards")
@RequiredArgsConstructor
public class PublicCardController {

    private final CardShareService cardShares;

    @GetMapping("/{token}")
    public ResponseEntity<PublicCardResponse> card(@PathVariable String token) {
        // Not cached: the owner can turn the link off or change fields at any time.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cardShares.publicCard(token));
    }
}
