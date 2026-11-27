package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Holds the aggregate states for one immutable group key.
 */
final class GroupAccumulator {
    private final List<String> groupByColumns;
    private final List<String> key;
    private final List<AggregateSpecification> specifications;
    private final List<AggregateState> states;

    GroupAccumulator(
            List<String> groupByColumns,
            List<String> key,
            List<AggregateSpecification> specifications) {
        this.groupByColumns = validateGroupByColumns(groupByColumns);
        this.key = immutableNullableList(key, "Group key must not be null");
        if (this.key.size() != this.groupByColumns.size()) {
            throw new IllegalArgumentException("Group key size must match GROUP BY columns");
        }
        this.specifications = validateSpecifications(specifications, this.groupByColumns);
        this.states = this.specifications.stream()
                .map(AggregateStateFactory::create)
                .toList();
    }

    List<String> key() {
        return key;
    }

    List<AggregateSpecification> specifications() {
        return specifications;
    }

    List<String> groupByColumns() {
        return groupByColumns;
    }

    void add(Row row) {
        Objects.requireNonNull(row, "Input row must not be null");
        for (int index = 0; index < groupByColumns.size(); index++) {
            String column = groupByColumns.get(index);
            if (!row.values().containsKey(column)) {
                throw new IllegalArgumentException(
                        "Group-by column '" + column + "' is missing from row");
            }
            if (!Objects.equals(key.get(index), row.values().get(column))) {
                throw new IllegalArgumentException("Input row does not match group key");
            }
        }
        for (AggregateState state : states) {
            state.add(row);
        }
    }

    void merge(GroupAccumulator other) {
        Objects.requireNonNull(other, "Group accumulator must not be null");
        if (!key.equals(other.key)
                || !groupByColumns.equals(other.groupByColumns)
                || !specifications.equals(other.specifications)) {
            throw new IllegalArgumentException(
                    "Cannot merge group accumulators with incompatible keys or specifications");
        }
        for (int index = 0; index < states.size(); index++) {
            states.get(index).merge(other.states.get(index));
        }
    }

    GroupAccumulator copy() {
        GroupAccumulator copy = new GroupAccumulator(groupByColumns, key, specifications);
        copy.merge(this);
        return copy;
    }

    Row result() {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < groupByColumns.size(); index++) {
            values.put(groupByColumns.get(index), key.get(index));
        }
        for (int index = 0; index < specifications.size(); index++) {
            values.put(specifications.get(index).outputColumn(), states.get(index).result());
        }
        return new Row(values);
    }

    static void validateConfiguration(
            List<String> groupByColumns, List<AggregateSpecification> specifications) {
        List<String> groups = validateGroupByColumns(groupByColumns);
        validateSpecifications(specifications, groups);
    }

    private static List<String> validateGroupByColumns(List<String> columns) {
        Objects.requireNonNull(columns, "Group-by columns must not be null");
        List<String> copy = new ArrayList<>(columns.size());
        Set<String> seen = new HashSet<>();
        for (String column : columns) {
            if (column == null || column.isBlank()) {
                throw new IllegalArgumentException("Group-by column must not be blank");
            }
            if (!seen.add(column)) {
                throw new IllegalArgumentException("Duplicate group-by column: " + column);
            }
            copy.add(column);
        }
        return List.copyOf(copy);
    }

    private static List<AggregateSpecification> validateSpecifications(
            List<AggregateSpecification> specifications, List<String> groupByColumns) {
        Objects.requireNonNull(specifications, "Aggregate specifications must not be null");
        if (specifications.isEmpty()) {
            throw new IllegalArgumentException("At least one aggregate specification is required");
        }
        Set<String> outputNames = new HashSet<>(groupByColumns);
        List<AggregateSpecification> copy = new ArrayList<>(specifications.size());
        for (AggregateSpecification specification : specifications) {
            Objects.requireNonNull(specification, "Aggregate specification must not be null");
            if (!outputNames.add(specification.outputColumn())) {
                throw new IllegalArgumentException("Duplicate aggregate output column: "
                        + specification.outputColumn());
            }
            copy.add(specification);
        }
        return List.copyOf(copy);
    }

    private static List<String> immutableNullableList(List<String> values, String nullMessage) {
        Objects.requireNonNull(values, nullMessage);
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
