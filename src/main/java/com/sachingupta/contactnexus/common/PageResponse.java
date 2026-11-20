package com.sachingupta.contactnexus.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * JSON-stable wrapper around Spring's {@link Page}. Spring Data serializes
 * {@code PageImpl} directly but logs a warning in Boot 3+ because the shape
 * (pageable, sort details, etc.) is considered internal. This record exposes
 * exactly the fields a paginated UI needs and nothing more, so the JSON
 * contract stays stable across future Spring upgrades.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                page.hasPrevious());
    }
}
