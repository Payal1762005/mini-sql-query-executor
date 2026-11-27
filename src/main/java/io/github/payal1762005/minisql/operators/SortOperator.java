package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.OrderByItem;
import io.github.payal1762005.minisql.model.OrderDirection;
import io.github.payal1762005.minisql.model.Row;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Materializes and stably sorts its child rows on first access.
 */
final class SortOperator implements Operator {
    private final Operator child;
    private final List<OrderByItem> orderByItems;
    private List<Row> sortedRows = new ArrayList<>();
    private Iterator<Row> resultIterator;
    private boolean sorted;
    private boolean childClosed;
    private boolean closed;

    SortOperator(Operator child, List<OrderByItem> orderByItems) {
        this.child = Objects.requireNonNull(child, "child operator must not be null");
        Objects.requireNonNull(orderByItems, "ORDER BY items must not be null");
        if (orderByItems.isEmpty()) {
            throw new IllegalArgumentException("At least one ORDER BY item is required");
        }
        List<OrderByItem> copy = new ArrayList<>(orderByItems.size());
        for (OrderByItem item : orderByItems) {
            copy.add(Objects.requireNonNull(item, "ORDER BY item must not be null"));
        }
        this.orderByItems = List.copyOf(copy);
    }

    @Override
    public boolean hasNext() {
        if (closed) {
            return false;
        }
        try {
            sortIfNeeded();
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
            throw new NoSuchElementException("No sorted rows remain");
        }
        return resultIterator.next();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        sortedRows.clear();
        resultIterator = Collections.emptyIterator();
        closeChild();
    }

    private void sortIfNeeded() {
        if (sorted) {
            return;
        }

        try {
            Map<String, Boolean> numericColumns = new HashMap<>();
            for (OrderByItem item : orderByItems) {
                numericColumns.put(item.columnName(), true);
            }

            while (child.hasNext()) {
                Row row = Objects.requireNonNull(child.next(), "Child operator returned a null row");
                for (OrderByItem item : orderByItems) {
                    String column = item.columnName();
                    if (!row.values().containsKey(column)) {
                        throw new IllegalArgumentException(
                                "ORDER BY column '" + column + "' is missing from row");
                    }
                    String value = row.values().get(column);
                    if (value != null && numericColumns.get(column)) {
                        try {
                            new BigDecimal(value);
                        } catch (NumberFormatException ignored) {
                            numericColumns.put(column, false);
                        }
                    }
                }
                sortedRows.add(row);
            }
            closeChild();
            sortedRows.sort(comparator(numericColumns));
            resultIterator = sortedRows.iterator();
            sorted = true;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    private java.util.Comparator<Row> comparator(Map<String, Boolean> numericColumns) {
        return (left, right) -> {
            for (OrderByItem item : orderByItems) {
                String leftValue = left.values().get(item.columnName());
                String rightValue = right.values().get(item.columnName());
                if (leftValue == null || rightValue == null) {
                    if (leftValue != rightValue) {
                        return leftValue == null ? 1 : -1;
                    }
                    continue;
                }
                int comparison = numericColumns.get(item.columnName())
                        ? new BigDecimal(leftValue).compareTo(new BigDecimal(rightValue))
                        : leftValue.compareTo(rightValue);
                if (comparison != 0) {
                    return item.direction() == OrderDirection.DESC ? -comparison : comparison;
                }
            }
            return 0;
        };
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
}
