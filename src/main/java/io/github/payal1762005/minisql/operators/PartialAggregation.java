package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A local insertion-ordered collection of group accumulators for a set of rows.
 */
final class PartialAggregation {
    private final List<String> groupByColumns;
    private final List<AggregateSpecification> specifications;
    private final LinkedHashMap<List<String>, GroupAccumulator> groups = new LinkedHashMap<>();

    PartialAggregation(
            List<String> groupByColumns,
            List<AggregateSpecification> specifications) {
        this.groupByColumns = immutableCopy(groupByColumns, "Group-by columns must not be null");
        this.specifications = immutableCopy(specifications,
                "Aggregate specifications must not be null");
        validateConfiguration();
        if (this.groupByColumns.isEmpty()) {
            groups.put(List.of(), new GroupAccumulator(
                    this.groupByColumns, List.of(), this.specifications));
        }
    }

    PartialAggregation(
            Collection<Row> rows,
            List<String> groupByColumns,
            List<AggregateSpecification> specifications) {
        this(groupByColumns, specifications);
        Objects.requireNonNull(rows, "Input rows must not be null");
        rows.forEach(this::add);
    }

    void add(Row row) {
        Objects.requireNonNull(row, "Input row must not be null");
        List<String> keyValues = new ArrayList<>(groupByColumns.size());
        for (String column : groupByColumns) {
            if (!row.values().containsKey(column)) {
                throw new IllegalArgumentException(
                        "Group-by column '" + column + "' is missing from row");
            }
            keyValues.add(row.values().get(column));
        }
        List<String> key = Collections.unmodifiableList(keyValues);
        GroupAccumulator group = groups.computeIfAbsent(key,
                ignored -> new GroupAccumulator(groupByColumns, key, specifications));
        group.add(row);
    }

    void merge(PartialAggregation other) {
        Objects.requireNonNull(other, "Partial aggregation must not be null");
        if (!groupByColumns.equals(other.groupByColumns)
                || !specifications.equals(other.specifications)) {
            throw new IllegalArgumentException(
                    "Cannot merge partial aggregations with incompatible specifications");
        }
        for (Map.Entry<List<String>, GroupAccumulator> entry : other.groups.entrySet()) {
            GroupAccumulator current = groups.get(entry.getKey());
            if (current == null) {
                groups.put(entry.getKey(), entry.getValue().copy());
            } else {
                current.merge(entry.getValue());
            }
        }
    }

    List<Row> resultRows() {
        return groups.values().stream().map(GroupAccumulator::result).toList();
    }

    private void validateConfiguration() {
        GroupAccumulator.validateConfiguration(groupByColumns, specifications);
    }

    private static <T> List<T> immutableCopy(List<T> values, String nullMessage) {
        Objects.requireNonNull(values, nullMessage);
        return List.copyOf(values);
    }
}
