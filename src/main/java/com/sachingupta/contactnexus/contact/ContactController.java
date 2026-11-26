package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.common.PageResponse;
import com.sachingupta.contactnexus.contact.dto.ContactImportRequest;
import com.sachingupta.contactnexus.contact.dto.ContactImportResult;
import com.sachingupta.contactnexus.contact.dto.ContactRequest;
import com.sachingupta.contactnexus.contact.dto.ContactResponse;
import com.sachingupta.contactnexus.contact.dto.ContactStats;
import com.sachingupta.contactnexus.contact.dto.DuplicateGroupResponse;
import com.sachingupta.contactnexus.contact.dto.FavoriteRequest;
import com.sachingupta.contactnexus.contact.dto.TagCount;
import com.sachingupta.contactnexus.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/contacts")
@RequiredArgsConstructor
public class ContactController {

    /** Whitelisted so clients can't sort on internal columns such as owner_id or image_key. */
    private static final Set<String> SORTABLE = Set.of("firstName", "lastName", "email", "createdAt", "updatedAt", "favorite");

    private final ContactService service;
    private final ContactMapper mapper;

    /**
     * Multiple sorts are allowed, e.g. {@code sort=favorite,desc&sort=firstName,asc}
     * for "favourites first".
     */
    @GetMapping
    public PageResponse<ContactResponse> list(@AuthenticationPrincipal AuthUser principal,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "false") boolean favorite,
                                              @RequestParam(required = false) String tag,
                                              Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cannot sort by '%s'; allowed: %s".formatted(order.getProperty(), SORTABLE));
            }
        }
        return PageResponse.from(service.list(principal.getId(), q, favorite, tag, pageable).map(mapper::toResponse));
    }

    @GetMapping("/stats")
    public ContactStats stats(@AuthenticationPrincipal AuthUser principal) {
        return service.stats(principal.getId());
    }

    @GetMapping("/tags")
    public List<TagCount> tags(@AuthenticationPrincipal AuthUser principal) {
        return service.tags(principal.getId());
    }

    /** Removes the tag from every contact that has it. */
    @DeleteMapping("/tags/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTag(@AuthenticationPrincipal AuthUser principal, @PathVariable String name) {
        service.deleteTag(principal.getId(), name);
    }

    @GetMapping("/duplicates")
    public List<DuplicateGroupResponse> duplicates(@AuthenticationPrincipal AuthUser principal) {
        return service.duplicates(principal.getId()).stream()
                .map(g -> new DuplicateGroupResponse(g.reason(), g.value(), mapper.toResponses(g.contacts())))
                .toList();
    }

    /** Bulk create from a parsed .vcf / .csv; duplicates (by email) are skipped, not errors. */
    @PostMapping("/import")
    public ContactImportResult importContacts(@AuthenticationPrincipal AuthUser principal,
                                              @Valid @RequestBody ContactImportRequest body) {
        return service.importAll(principal.getId(), body.contacts());
    }

    @PutMapping("/{id}/favorite")
    public ContactResponse setFavorite(@AuthenticationPrincipal AuthUser principal,
                                       @PathVariable Long id,
                                       @Valid @RequestBody FavoriteRequest body) {
        return mapper.toResponse(service.setFavorite(principal.getId(), id, body.favorite()));
    }

    @PutMapping(path = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ContactResponse uploadPhoto(@AuthenticationPrincipal AuthUser principal,
                                       @PathVariable Long id,
                                       @RequestParam("file") MultipartFile file) {
        return mapper.toResponse(service.setPhoto(principal.getId(), id, file));
    }

    @DeleteMapping("/{id}/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePhoto(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        service.removePhoto(principal.getId(), id);
    }

    @GetMapping("/{id}")
    public ContactResponse get(@AuthenticationPrincipal AuthUser principal,
                               @PathVariable Long id) {
        return mapper.toResponse(service.get(principal.getId(), id));
    }

    @PostMapping
    public ResponseEntity<ContactResponse> create(@AuthenticationPrincipal AuthUser principal,
                                                  @Valid @RequestBody ContactRequest req) {
        Contact created = service.create(principal.getId(), req);
        return ResponseEntity
                .created(URI.create("/api/v1/contacts/" + created.getId()))
                .body(mapper.toResponse(created));
    }

    @PutMapping("/{id}")
    public ContactResponse update(@AuthenticationPrincipal AuthUser principal,
                                  @PathVariable Long id,
                                  @Valid @RequestBody ContactRequest req) {
        return mapper.toResponse(service.update(principal.getId(), id, req));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser principal,
                       @PathVariable Long id) {
        service.delete(principal.getId(), id);
    }
}
