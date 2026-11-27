package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterOperatorTest {
    @Test
    void returnsOnlyMatchingRowsInInputOrder() {
        FilterOperator filter = new FilterOperator(
                new ListOperator(rows(1, 2, 3, 4)),
                row -> Integer.parseInt(row.values().get("id")) % 2 == 0);

        assertEquals(List.of(row(2), row(4)), consume(filter));
    }

    @Test
    void readsOnlyUntilNextMatchAndBuffersAcrossRepeatedHasNext() {
        AtomicInteger reads = new AtomicInteger();
        Operator child = new ListOperator(rows(1, 2, 3)) {
            @Override
            public Row next() {
                reads.incrementAndGet();
                return super.next();
            }
        };
        FilterOperator filter = new FilterOperator(child,
                candidate -> candidate.equals(row(2)));

        assertTrue(filter.hasNext());
        assertTrue(filter.hasNext());
        assertEquals(2, reads.get());
        assertEquals(row(2), filter.next());
        assertFalse(filter.hasNext());
    }

    @Test
    void handlesEmptyInputAndClosesChildOnExhaustion() {
        AtomicBoolean childClosed = new AtomicBoolean();
        FilterOperator filter = new FilterOperator(
                new ListOperator(List.of(), childClosed), row -> true);

        assertFalse(filter.hasNext());
        assertFalse(filter.hasNext());
        assertTrue(childClosed.get());
        assertThrows(NoSuchElementException.class, filter::next);
    }

    @Test
    void explicitCloseClosesChildAndPreventsReads() {
        AtomicBoolean childClosed = new AtomicBoolean();
        FilterOperator filter = new FilterOperator(
                new ListOperator(rows(1, 2), childClosed), row -> true);

        filter.close();
        filter.close();

        assertTrue(childClosed.get());
        assertFalse(filter.hasNext());
        assertThrows(NoSuchElementException.class, filter::next);
    }

    @Test
    void closesChildWhenPredicateFailsAndPropagatesFailure() {
        AtomicBoolean childClosed = new AtomicBoolean();
        IllegalArgumentException expected = new IllegalArgumentException("bad predicate");
        FilterOperator filter = new FilterOperator(
                new ListOperator(rows(1), childClosed),
                row -> { throw expected; });

        assertEquals(expected, assertThrows(IllegalArgumentException.class, filter::hasNext));
        assertTrue(childClosed.get());
        assertFalse(filter.hasNext());
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

    private List<Row> rows(int... ids) {
        List<Row> rows = new ArrayList<>(ids.length);
        for (int id : ids) {
            rows.add(row(id));
        }
        return rows;
    }

    private Row row(int id) {
        return new Row(Map.of("id", Integer.toString(id)));
    }

    private static class ListOperator implements Operator {
        private final List<Row> rows;
        private final AtomicBoolean closed;
        private int index;

        private ListOperator(List<Row> rows) {
            this(rows, new AtomicBoolean());
        }

        private ListOperator(List<Row> rows, AtomicBoolean closed) {
            this.rows = rows;
            this.closed = closed;
        }

        @Override
        public boolean hasNext() {
            return !closed.get() && index < rows.size();
        }

        @Override
        public Row next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return rows.get(index++);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
