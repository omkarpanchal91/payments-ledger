package com.omkar.ledger.common;

import java.util.List;

/**
 * Cursor-paginated list (Stripe style). To get the next page, pass the id of the last item in
 * {@code data} as {@code starting_after}.
 */
public record PageResponse<T>(String object, List<T> data, boolean hasMore) {

    public static <T> PageResponse<T> of(List<T> data, boolean hasMore) {
        return new PageResponse<>("list", data, hasMore);
    }
}
