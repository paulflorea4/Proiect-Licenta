package com.gradingplatform.backend.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * The one shape of every paginated list (2.5c, chosen by the human): our own envelope, so the JSON
 * does not follow Spring Data's internals.
 *
 * <p>Query parameters: {@code ?page=0&size=20}. {@code page} is 0-based (default 0), {@code size}
 * defaults to {@link #DEFAULT_SIZE} and is clamped to {@link #MAX_SIZE}; a negative page or a size
 * below 1 is a 400, and so is a page whose first row would be beyond {@link Integer#MAX_VALUE}
 * (JPA's offset limit; {@link PageOutOfRangeException}). An empty {@code page=} counts as absent. A page past the end is not an error: {@code items} is empty and the totals are
 * still right. Every list has a fixed, stable order, so pages never overlap.
 *
 * @param items the rows of this page
 * @param page the 0-based page number
 * @param size the page size actually used (after clamping), not the number of items
 * @param totalElements rows across all pages
 * @param totalPages pages needed for {@code totalElements} at this size
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /** The request for a page: {@code null} means the default, an oversized {@code size} is clamped. */
    public static Pageable pageable(Integer page, Integer size, Sort sort) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        // JPA takes the row offset as an int; a larger one would overflow into a 500.
        if ((long) pageNumber * pageSize > Integer.MAX_VALUE) {
            throw new PageOutOfRangeException();
        }
        return PageRequest.of(pageNumber, pageSize, sort);
    }

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> toDto) {
        return new PageResponse<>(
                page.getContent().stream().map(toDto).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
