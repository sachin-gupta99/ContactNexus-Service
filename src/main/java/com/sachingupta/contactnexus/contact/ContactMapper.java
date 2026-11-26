package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.contact.dto.ContactResponse;
import com.sachingupta.contactnexus.user.AvatarStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds API responses. Uploaded photos are private S3 objects, so their key is
 * turned into a presigned URL here (signing is local, no network call).
 */
@Component
@RequiredArgsConstructor
public class ContactMapper {

    private final AvatarStorage storage;

    public ContactResponse toResponse(Contact c) {
        String imageUrl = c.getImageKey() != null ? storage.presignedUrl(c.getImageKey()) : c.getImageUrl();
        List<String> tags = c.getTags().stream()
                .map(Tag::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return ContactResponse.from(c, imageUrl, c.getImageKey() != null, tags);
    }

    public List<ContactResponse> toResponses(List<Contact> contacts) {
        return contacts.stream().map(this::toResponse).toList();
    }
}
