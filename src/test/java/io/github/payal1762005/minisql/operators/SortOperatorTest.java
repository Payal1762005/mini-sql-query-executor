package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.OrderByItem;
import io.github.payal1762005.minisql.model.OrderDirection;
import io.github.payal1762005.minisql.model.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortOperatorTest {
    @Test
    void sortsMultipleColumnsWithIndependentDirections() {
        List<Row> input = List.of(
                row("department", "Sales", "salary", "40", "id", "1"),
                row("department", "Engineering", "salary", "50", "id", "2"),
                row("department", "Sales", "salary", "80", "id", "3"),
                row("department", "Engineering", "salary", "20", "id", "4"));
        SortOperator sort = new SortOperator(new TrackingOperator(input), List.of(
                item("department", OrderDirection.ASC),
                item("salary", OrderDirection.DESC)));

        assertEquals(List.of(input.get(1), input.get(3), input.get(2), input.get(0)),
                consume(sort));
    }

    @Test
    void comparesNumericColumnsAsBigDecimals() {
        List<Row> input = List.of(row("value", "10"), row("value", "2"),
                row("value", "1.25"), row("value", "-3"));

        assertEquals(List.of(input.get(3), input.get(2), input.get(1), input.get(0)),
                consume(new SortOperator(new TrackingOperator(input),
                        List.of(new OrderByItem("value")))));
    }

    @Test
    void comparesEntireColumnLexicallyWhenAnyNonNullValueIsNotNumeric() {
        List<Row> input = List.of(row("value", "2"), row("value", "10"),
                row("value", "apple"));

        assertEquals(List.of(input.get(1), input.get(0), input.get(2)),
                consume(new SortOperator(new TrackingOperator(input),
                        List.of(new OrderByItem("value")))));
    }

    @Test
    void keepsNullsLastForAscendingAndDescending() {
        List<Row> input = List.of(row("value", "2"), rowWithNull("value"),
                row("value", "10"));
        List<Row> ascending = List.of(input.get(0), input.get(2), input.get(1));
        List<Row> descending = List.of(input.get(2), input.get(0), input.get(1));

        assertEquals(ascending, consume(new SortOperator(new TrackingOperator(input),
                List.of(item("value", OrderDirection.ASC)))));
        assertEquals(descending, consume(new SortOperator(new TrackingOperator(input),
                List.of(item("value", OrderDirection.DESC)))));
    }

    @Test
    void preservesInputOrderForEqualSortKeys() {
        List<Row> input = List.of(
                row("department", "Sales", "id", "first"),
                row("department", "Sales", "id", "second"),
                row("department", "Sales", "id", "third"));

        assertEquals(input, consume(new SortOperator(new TrackingOperator(input),
                List.of(new OrderByItem("department")))));
    }

    @Test
    void materializesOnlyOnFirstAccessAndRepeatedHasNextDoesNotReadAgain() {
        TrackingOperator child = new TrackingOperator(rows(3, 1, 2));
        SortOperator sort = new SortOperator(child, List.of(new OrderByItem("value")));

        assertEquals(0, child.reads);
        assertTrue(sort.hasNext());
        assertTrue(sort.hasNext());
        assertEquals(3, child.reads);
        assertTrue(child.closed);
        assertEquals(row("value", "1"), sort.next());
        assertEquals(3, child.reads);
    }

    @Test
    void handlesEmptyInputAndClosesChild() {
        TrackingOperator child = new TrackingOperator(List.of());
        SortOperator sort = new SortOperator(child, List.of(new OrderByItem("value")));

        assertFalse(sort.hasNext());
        assertTrue(child.closed);
        assertThrows(NoSuchElementException.class, sort::next);
    }

    @Test
    void explicitCloseBeforeMaterializationIsIdempotent() {
        TrackingOperator child = new TrackingOperator(rows(1));
        SortOperator sort = new SortOperator(child, List.of(new OrderByItem("value")));

        sort.close();
        sort.close();

        assertTrue(child.closed);
        assertEquals(0, child.hasNextCalls);
        assertFalse(sort.hasNext());
        assertThrows(NoSuchElementException.class, sort::next);
    }

    @Test
    void closesChildAndPropagatesMissingColumnFailure() {
        TrackingOperator child = new TrackingOperator(List.of(row("other", "x")));
        SortOperator sort = new SortOperator(child, List.of(new OrderByItem("value")));

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, sort::hasNext);

        assertTrue(failure.getMessage().contains("value"));
        assertTrue(child.closed);
        assertFalse(sort.hasNext());
    }

    @Test
    void preservesPrimaryFailureAndSuppressesChildCloseFailure() {
        IllegalStateException failure = new IllegalStateException("iteration failed");
        IllegalArgumentException closeFailure = new IllegalArgumentException("close failed");
        TrackingOperator child = new TrackingOperator(List.of()) {
            @Override
            public boolean hasNext() {
                throw failure;
            }

            @Override
            public void close() {
                super.close();
                throw closeFailure;
            }
        };
        SortOperator sort = new SortOperator(child, List.of(new OrderByItem("value")));

        assertSame(failure, assertThrows(IllegalStateException.class, sort::hasNext));

        assertEquals(List.of(closeFailure), List.of(failure.getSuppressed()));
        assertTrue(child.closed);
        assertFalse(sort.hasNext());
    }

    @Test
    void propagatesChildCloseFailureAfterMaterialization() {
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        TrackingOperator child = new TrackingOperator(rows(1)) {
            @Override
            public void close() {
                super.close();
                throw closeFailure;
            }
        };
        SortOperator sort = new SortOperator(child, List.of(new OrderByItem("value")));

        assertSame(closeFailure, assertThrows(IllegalStateException.class, sort::hasNext));
        assertTrue(child.closed);
        assertFalse(sort.hasNext());
    }

    @Test
    void validatesOrderByItems() {
        assertThrows(IllegalArgumentException.class,
                () -> new SortOperator(new TrackingOperator(List.of()), List.of()));
        assertThrows(NullPointerException.class,
                () -> new SortOperator(new TrackingOperator(List.of()), null));
    }

    private List<Row> consume(Operator operator) {
        List<Row> result = new ArrayList<>();
        try (operator) {
            while (operator.hasNext()) {
                result.add(operator.next());
            }
        }
        return result;
    }

    private List<Row> rows(int... values) {
        List<Row> result = new ArrayList<>(values.length);
        for (int value : values) {
            result.add(row("value", Integer.toString(value)));
        }
        return result;
    }

    private OrderByItem item(String column, OrderDirection direction) {
        return new OrderByItem(column, direction);
    }

    private Row row(String key, String value) {
        return new Row(Map.of(key, value));
    }

    private Row row(String key1, String value1, String key2, String value2) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        return new Row(values);
    }

    private Row row(String key1, String value1, String key2, String value2,
                    String key3, String value3) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        values.put(key3, value3);
        return new Row(values);
    }

    private Row rowWithNull(String key) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, null);
        return new Row(values);
    }

    private static class TrackingOperator implements Operator {
        private final List<Row> rows;
        private int index;
        private int reads;
        private int hasNextCalls;
        private boolean closed;

        private TrackingOperator(List<Row> rows) {
            this.rows = rows;
        }

        @Override
        public boolean hasNext() {
            hasNextCalls++;
            return !closed && index < rows.size();
        }

        @Override
        public Row next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            reads++;
            return rows.get(index++);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
