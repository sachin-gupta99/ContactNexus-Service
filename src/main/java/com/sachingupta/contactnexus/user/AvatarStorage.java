package com.sachingupta.contactnexus.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/**
 * Local-disk avatar storage. Swapped for S3 when profile/image handling lands.
 */
@Component
public class AvatarStorage {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif");

    private final Path uploadDir;
    private final String publicBaseUrl;

    public AvatarStorage(@Value("${app.upload-dir:uploads}") String uploadDir,
                         @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * @return the publicly reachable URL of the stored avatar
     */
    public String store(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No file was uploaded");
        }

        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Unsupported image type; allowed: " + ALLOWED_EXTENSIONS);
        }

        // Random name, never the client-supplied one, so a crafted filename cannot
        // escape the upload directory or overwrite another user's avatar.
        String filename = "%d-%s.%s".formatted(userId, UUID.randomUUID(), extension);

        try {
            Files.createDirectories(uploadDir);
            Path target = uploadDir.resolve(filename).normalize();
            if (!target.startsWith(uploadDir)) {
                throw new ResponseStatusException(BAD_REQUEST, "Invalid upload path");
            }
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Could not store the uploaded image", ex);
        }

        return "%s/uploads/%s".formatted(publicBaseUrl, filename);
    }

    public Path getUploadDir() {
        return uploadDir;
    }

    private String extensionOf(String originalFilename) {
        String ext = StringUtils.getFilenameExtension(originalFilename);
        return ext == null ? "" : ext.toLowerCase(Locale.ROOT);
    }
}
