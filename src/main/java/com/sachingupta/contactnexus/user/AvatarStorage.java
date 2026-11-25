package com.sachingupta.contactnexus.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/**
 * Avatar storage in a private S3 bucket. Only the object key is persisted; clients
 * get a short-lived presigned GET URL, so the bucket never needs public access.
 */
@Component
public class AvatarStorage {

    private static final Logger log = LoggerFactory.getLogger(AvatarStorage.class);

    // Content type is derived from the extension rather than the client header, so a
    // caller cannot get e.g. text/html served back from our bucket under a .png name.
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif");

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration presignTtl;

    public AvatarStorage(S3Client s3,
                         S3Presigner presigner,
                         @Value("${aws.s3.bucket}") String bucket,
                         @Value("${aws.s3.presign-ttl:PT12H}") Duration presignTtl) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.presignTtl = presignTtl;
    }

    /**
     * @return the S3 object key of the stored avatar
     */
    public String store(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No file was uploaded");
        }

        String extension = extensionOf(file.getOriginalFilename());
        String contentType = CONTENT_TYPES.get(extension);
        if (contentType == null) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Unsupported image type; allowed: " + CONTENT_TYPES.keySet());
        }

        // Random name, never the client-supplied one, so a crafted filename cannot
        // overwrite another user's avatar.
        String key = "avatars/%d/%s.%s".formatted(userId, UUID.randomUUID(), extension);

        try (InputStream in = file.getInputStream()) {
            s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType),
                    RequestBody.fromInputStream(in, file.getSize()));
        } catch (IOException | SdkException ex) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Could not store the uploaded image", ex);
        }
        return key;
    }

    public String presignedUrl(String key) {
        return presigner.presignGetObject(b -> b
                        .signatureDuration(presignTtl)
                        .getObjectRequest(g -> g.bucket(bucket).key(key)))
                .url()
                .toString();
    }

    /**
     * Best-effort: a leftover object only costs storage, so a failed delete is logged
     * rather than failing the caller.
     */
    public void delete(String key) {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (SdkException ex) {
            log.warn("Could not delete old avatar {}: {}", key, ex.getMessage());
        }
    }

    private String extensionOf(String originalFilename) {
        String ext = StringUtils.getFilenameExtension(originalFilename);
        return ext == null ? "" : ext.toLowerCase(Locale.ROOT);
    }
}
