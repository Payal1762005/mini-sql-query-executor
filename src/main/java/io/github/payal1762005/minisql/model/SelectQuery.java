package io.github.payal1762005.minisql.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable abstract syntax tree for a supported SELECT query.
 */
public record SelectQuery(
        List<SelectItem> selectItems,
        String tableName,
        Optional<WhereExpression> where,
        List<String> groupByColumns,
        boolean explain,
        List<OrderByItem> orderByItems,
        Optional<Integer> limit) implements QueryPlan {
    public SelectQuery {
        selectItems = List.copyOf(Objects.requireNonNull(selectItems, "SELECT items must not be null"));
        if (selectItems.isEmpty()) {
            throw new IllegalArgumentException("SELECT must contain at least one item");
        }
        if (selectItems.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("SELECT items must not contain null");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name must not be blank");
        }
        Objects.requireNonNull(where, "WHERE expression optional must not be null");
        groupByColumns = List.copyOf(
                Objects.requireNonNull(groupByColumns, "GROUP BY columns must not be null"));
        if (groupByColumns.stream().anyMatch(column -> column == null || column.isBlank())) {
            throw new IllegalArgumentException("GROUP BY columns must not be blank");
        }
        orderByItems = List.copyOf(
                Objects.requireNonNull(orderByItems, "ORDER BY items must not be null"));
        if (orderByItems.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("ORDER BY items must not contain null");
        }
        Objects.requireNonNull(limit, "LIMIT optional must not be null");
        if (limit.isPresent() && limit.orElseThrow() < 0) {
            throw new IllegalArgumentException("LIMIT must not be negative");
        }
    }

    public SelectQuery(List<SelectItem> selectItems, String tableName,
                       Optional<WhereExpression> where, List<String> groupByColumns,
                       boolean explain) {
        this(selectItems, tableName, where, groupByColumns, explain, List.of(), Optional.empty());
    }

    public SelectQuery(List<SelectItem> selectItems, String tableName,
                       Optional<WhereExpression> where, List<String> groupByColumns) {
        this(selectItems, tableName, where, groupByColumns, false, List.of(), Optional.empty());
    }
}
