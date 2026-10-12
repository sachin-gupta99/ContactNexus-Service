package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.contact.dto.ContactRequest;
import com.sachingupta.contactnexus.contact.dto.ContactResponse;
import com.sachingupta.contactnexus.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService service;

    @GetMapping
    public Page<ContactResponse> list(@AuthenticationPrincipal AuthUser principal,
                                      @RequestParam(required = false) String q,
                                      Pageable pageable) {
        return service.list(principal.getId(), q, pageable).map(ContactResponse::from);
    }

    @GetMapping("/{id}")
    public ContactResponse get(@AuthenticationPrincipal AuthUser principal,
                               @PathVariable Long id) {
        return ContactResponse.from(service.get(principal.getId(), id));
    }

    @PostMapping
    public ResponseEntity<ContactResponse> create(@AuthenticationPrincipal AuthUser principal,
                                                  @Valid @RequestBody ContactRequest req) {
        Contact created = service.create(principal.getId(), req);
        return ResponseEntity
                .created(URI.create("/api/v1/contacts/" + created.getId()))
                .body(ContactResponse.from(created));
    }

    @PutMapping("/{id}")
    public ContactResponse update(@AuthenticationPrincipal AuthUser principal,
                                  @PathVariable Long id,
                                  @Valid @RequestBody ContactRequest req) {
        return ContactResponse.from(service.update(principal.getId(), id, req));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser principal,
                       @PathVariable Long id) {
        service.delete(principal.getId(), id);
    }
}
