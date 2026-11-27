package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Consumes its child once, retaining only per-group aggregate state, then
 * produces one result row per group in first-seen order.
 */
public class AggregateOperator implements Operator {
    public static final int AVG_SCALE = 4;
    public static final RoundingMode AVG_ROUNDING_MODE = RoundingMode.HALF_UP;

    private final Operator child;
    private final List<String> groupByColumns;
    private final List<AggregateSpecification> specifications;
    private final boolean globalAggregation;
    private LinkedHashMap<List<String>, GroupState> groups;
    private Iterator<GroupState> resultIterator;
    private boolean aggregated;
    private boolean childClosed;
    private boolean closed;

    public AggregateOperator(
            Operator child,
            List<String> groupByColumns,
            AggregateSpecification... specifications) {
        this(child, groupByColumns, List.of(specifications));
    }

    public AggregateOperator(
            Operator child,
            List<String> groupByColumns,
            List<AggregateSpecification> specifications) {
        this.child = Objects.requireNonNull(child, "child operator must not be null");
        this.groupByColumns = validateGroupColumns(groupByColumns);
        this.specifications = validateSpecifications(specifications, this.groupByColumns);
        this.globalAggregation = this.groupByColumns.isEmpty();
        this.groups = new LinkedHashMap<>();
    }

    @Override
    public boolean hasNext() {
        if (closed) {
            return false;
        }
        try {
            aggregateIfNeeded();
            if (resultIterator.hasNext()) {
                return true;
            }
            close();
            return false;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public Row next() {
        if (!hasNext()) {
            throw new NoSuchElementException("No aggregate result rows remain");
        }

        GroupState group = resultIterator.next();
        return createResult(group);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        groups.clear();
        resultIterator = Collections.emptyIterator();
        closeChild();
    }

    private void aggregateIfNeeded() {
        if (aggregated) {
            return;
        }

        try {
            if (globalAggregation) {
                groups.put(List.of(), new GroupState(List.of(), specifications));
            }

            while (child.hasNext()) {
                accumulate(child.next());
            }
            closeChild();
            resultIterator = groups.values().iterator();
            aggregated = true;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    private void accumulate(Row row) {
        Objects.requireNonNull(row, "Child operator returned a null row");
        List<String> keyValues = new ArrayList<>(groupByColumns.size());
        for (String column : groupByColumns) {
            if (!row.values().containsKey(column)) {
                throw new IllegalArgumentException("Group-by column '" + column + "' is missing from row");
            }
            keyValues.add(row.values().get(column));
        }
        List<String> key = Collections.unmodifiableList(keyValues);
        GroupState group = groups.computeIfAbsent(key, ignored -> new GroupState(key, specifications));
        group.addRow(row, specifications);
    }

    private Row createResult(GroupState group) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < groupByColumns.size(); index++) {
            values.put(groupByColumns.get(index), group.key.get(index));
        }
        for (int index = 0; index < specifications.size(); index++) {
            AggregateSpecification specification = specifications.get(index);
            values.put(specification.outputColumn(),
                    group.aggregates.get(index).result(specification));
        }
        return new Row(values);
    }

    private void closeChild() {
        if (!childClosed) {
            childClosed = true;
            child.close();
        }
    }

    private void closeAfterFailure(Throwable failure) {
        try {
            close();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static List<String> validateGroupColumns(List<String> columns) {
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

    private static final class GroupState {
        private List<String> key = List.of();
        private final List<AggregateState> aggregates = new ArrayList<>();

        private void addRow(Row row, List<AggregateSpecification> specifications) {
            for (int index = 0; index < specifications.size(); index++) {
                aggregates.get(index).add(row, specifications.get(index));
            }
        }

        private GroupState(List<String> key, List<AggregateSpecification> specifications) {
            this.key = key;
            for (int index = 0; index < specifications.size(); index++) {
                aggregates.add(new AggregateState());
            }
        }
    }

    private static final class AggregateState {
        private long count;
        private BigDecimal total = BigDecimal.ZERO;
        private BigDecimal extremum;

        private void add(Row row, AggregateSpecification specification) {
            String column = specification.inputColumn();
            if ("*".equals(column)) {
                count++;
                return;
            }
            if (!row.values().containsKey(column)) {
                throw new IllegalArgumentException("Aggregate input column '" + column + "' is missing from row");
            }
            String value = row.values().get(column);
            if (value == null) {
                return;
            }
            if (specification.function() == AggregateFunction.COUNT) {
                count++;
                return;
            }

            BigDecimal number = parseNumber(value, column);
            switch (specification.function()) {
                case SUM, AVG -> total = total.add(number);
                case MIN -> extremum = extremum == null || number.compareTo(extremum) < 0
                        ? number : extremum;
                case MAX -> extremum = extremum == null || number.compareTo(extremum) > 0
                        ? number : extremum;
                case COUNT -> throw new IllegalStateException("COUNT handled above");
            }
            count++;
        }

        private String result(AggregateSpecification specification) {
            return switch (specification.function()) {
                case COUNT -> Long.toString(count);
                case SUM -> count == 0 ? null : total.stripTrailingZeros().toPlainString();
                case AVG -> count == 0 ? null
                        : total.divide(BigDecimal.valueOf(count), AVG_SCALE, AVG_ROUNDING_MODE)
                        .toPlainString();
                case MIN, MAX -> extremum == null ? null : extremum.stripTrailingZeros().toPlainString();
            };
        }

        private static BigDecimal parseNumber(String value, String column) {
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid numeric value '" + value
                        + "' in aggregate input column '" + column + "'", exception);
            }
        }
    }
}
