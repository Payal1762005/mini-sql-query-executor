package io.github.payal1762005.minisql.model;

import java.util.Objects;

/**
 * One ordered column reference in an ORDER BY clause.
 */
public record OrderByItem(String columnName, OrderDirection direction) {
    public OrderByItem {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("ORDER BY column name must not be blank");
        }
        Objects.requireNonNull(direction, "ORDER BY direction must not be null");
    }

    public OrderByItem(String columnName) {
        this(columnName, OrderDirection.ASC);
    }
}
