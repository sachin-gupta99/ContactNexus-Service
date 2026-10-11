package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.contact.dto.ContactRequest;
import com.sachingupta.contactnexus.contact.dto.ContactResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService service;

    // TODO: replace header with authenticated principal once security is wired
    @GetMapping
    public Page<ContactResponse> list(@RequestHeader("X-User-Id") Long ownerId,
                                      @RequestParam(required = false) String q,
                                      Pageable pageable) {
        return service.list(ownerId, q, pageable).map(ContactResponse::from);
    }

    @GetMapping("/{id}")
    public ContactResponse get(@RequestHeader("X-User-Id") Long ownerId,
                               @PathVariable Long id) {
        return ContactResponse.from(service.get(ownerId, id));
    }

    @PostMapping
    public ResponseEntity<ContactResponse> create(@RequestHeader("X-User-Id") Long ownerId,
                                                  @Valid @RequestBody ContactRequest req) {
        Contact created = service.create(ownerId, req);
        return ResponseEntity
                .created(URI.create("/api/v1/contacts/" + created.getId()))
                .body(ContactResponse.from(created));
    }

    @PutMapping("/{id}")
    public ContactResponse update(@RequestHeader("X-User-Id") Long ownerId,
                                  @PathVariable Long id,
                                  @Valid @RequestBody ContactRequest req) {
        return ContactResponse.from(service.update(ownerId, id, req));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader("X-User-Id") Long ownerId,
                       @PathVariable Long id) {
        service.delete(ownerId, id);
    }
}
