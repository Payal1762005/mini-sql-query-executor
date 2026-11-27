package io.github.payal1762005.minisql.operators;

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

class LimitOperatorTest {
    @Test
    void emitsNoMoreThanLimitAndClosesImmediatelyAfterNthRow() {
        TrackingOperator child = new TrackingOperator(rows(1, 2, 3));
        LimitOperator limit = new LimitOperator(child, 2);

        assertTrue(limit.hasNext());
        assertTrue(limit.hasNext());
        assertEquals(0, child.reads);
        assertEquals(row("id", "1"), limit.next());
        assertFalse(child.closed);
        assertEquals(row("id", "2"), limit.next());

        assertTrue(child.closed);
        assertEquals(2, child.reads);
        assertFalse(limit.hasNext());
        assertThrows(NoSuchElementException.class, limit::next);
    }

    @Test
    void limitZeroDoesNotReadChildAndClosesOnFirstAccess() {
        TrackingOperator child = new TrackingOperator(rows(1));
        LimitOperator limit = new LimitOperator(child, 0);

        assertFalse(limit.hasNext());

        assertEquals(0, child.hasNextCalls);
        assertEquals(0, child.reads);
        assertTrue(child.closed);
        assertThrows(NoSuchElementException.class, limit::next);
    }

    @Test
    void handlesEmptyInputAndClosesChild() {
        TrackingOperator child = new TrackingOperator(List.of());
        LimitOperator limit = new LimitOperator(child, 4);

        assertFalse(limit.hasNext());
        assertFalse(limit.hasNext());
        assertTrue(child.closed);
        assertThrows(NoSuchElementException.class, limit::next);
    }

    @Test
    void explicitCloseIsIdempotentAndPreventsChildReads() {
        TrackingOperator child = new TrackingOperator(rows(1, 2));
        LimitOperator limit = new LimitOperator(child, 2);

        limit.close();
        limit.close();

        assertTrue(child.closed);
        assertEquals(0, child.hasNextCalls);
        assertFalse(limit.hasNext());
        assertThrows(NoSuchElementException.class, limit::next);
    }

    @Test
    void rejectsNegativeLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new LimitOperator(new TrackingOperator(List.of()), -1));
    }

    @Test
    void propagatesChildFailureAndSuppressesCloseFailure() {
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
        LimitOperator limit = new LimitOperator(child, 1);

        assertSame(failure, assertThrows(IllegalStateException.class, limit::hasNext));
        assertEquals(List.of(closeFailure), List.of(failure.getSuppressed()));
        assertTrue(child.closed);
        assertFalse(limit.hasNext());
    }

    @Test
    void propagatesNextFailureAndClosesChild() {
        IllegalStateException failure = new IllegalStateException("next failed");
        TrackingOperator child = new TrackingOperator(rows(1)) {
            @Override
            public Row next() {
                throw failure;
            }
        };
        LimitOperator limit = new LimitOperator(child, 1);

        assertSame(failure, assertThrows(IllegalStateException.class, limit::next));
        assertTrue(child.closed);
    }

    @Test
    void propagatesCloseFailureAfterEmittingLimit() {
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        TrackingOperator child = new TrackingOperator(rows(1)) {
            @Override
            public void close() {
                super.close();
                throw closeFailure;
            }
        };
        LimitOperator limit = new LimitOperator(child, 1);

        assertSame(closeFailure, assertThrows(IllegalStateException.class, limit::next));
        assertTrue(child.closed);
        assertFalse(limit.hasNext());
    }

    private List<Row> rows(int... ids) {
        List<Row> result = new ArrayList<>(ids.length);
        for (int id : ids) {
            result.add(row("id", Integer.toString(id)));
        }
        return result;
    }

    private Row row(String key, String value) {
        return new Row(Map.of(key, value));
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
