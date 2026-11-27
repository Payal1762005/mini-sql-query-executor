package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionOperatorTest {
    @Test
    void selectsOneColumn() {
        ProjectionOperator projection = projection(
                rows(row("id", "7", "name", "Ava")), List.of("name"));

        assertEquals(row("name", "Ava"), projection.next());
    }

    @Test
    void selectsMultipleColumnsInRequestedOrder() {
        ProjectionOperator projection = projection(
                rows(row("id", "7", "name", "Ava", "department", "Engineering")),
                List.of("department", "id"));

        Row projected = projection.next();
        assertEquals(List.of("department", "id"), new ArrayList<>(projected.values().keySet()));
        assertEquals("Engineering", projected.values().get("department"));
        assertEquals("7", projected.values().get("id"));
    }

    @Test
    void preservesNullValues() {
        ProjectionOperator projection = projection(
                rows(rowWithNull("name", "department", "Engineering")),
                List.of("name", "department"));

        Row projected = projection.next();
        assertTrue(projected.values().containsKey("name"));
        assertNull(projected.values().get("name"));
        assertEquals("Engineering", projected.values().get("department"));
    }

    @Test
    void rejectsEmptyProjection() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectionOperator(new ListOperator(List.of()), List.of()));
    }

    @Test
    void rejectsInvalidProjectionColumnNamesAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectionOperator(new ListOperator(List.of()), List.of("id", " ")));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectionOperator(new ListOperator(List.of()), java.util.Arrays.asList("id", null)));
    }

    @Test
    void rejectsMissingColumnsOnFirstRequestedRowAndClosesChild() {
        AtomicBoolean childClosed = new AtomicBoolean();
        ProjectionOperator projection = new ProjectionOperator(
                new ListOperator(rows(row("id", "7")), childClosed), List.of("missing"));

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, projection::next);

        assertTrue(exception.getMessage().contains("missing"));
        assertTrue(childClosed.get());
        assertFalse(projection.hasNext());
    }

    @Test
    void projectsRowsLazilyAndRepeatedHasNextDoesNotConsumeMoreInput() {
        AtomicInteger reads = new AtomicInteger();
        ProjectionOperator projection = new ProjectionOperator(
                new ListOperator(rows(row("id", "1"), row("id", "2")),
                        new AtomicBoolean(), reads),
                List.of("id"));

        assertTrue(projection.hasNext());
        assertTrue(projection.hasNext());
        assertEquals(0, reads.get());
        assertEquals(row("id", "1"), projection.next());
        assertEquals(1, reads.get());
    }

    @Test
    void closesChildOnExhaustionAndNextAfterExhaustionThrows() {
        AtomicBoolean childClosed = new AtomicBoolean();
        ProjectionOperator projection = new ProjectionOperator(
                new ListOperator(rows(row("id", "1")), childClosed), List.of("id"));

        assertEquals(row("id", "1"), projection.next());
        assertFalse(projection.hasNext());
        assertTrue(childClosed.get());
        assertThrows(NoSuchElementException.class, projection::next);
    }

    @Test
    void closesChildOnExplicitCloseAndNextAfterCloseThrows() {
        AtomicBoolean childClosed = new AtomicBoolean();
        ProjectionOperator projection = new ProjectionOperator(
                new ListOperator(rows(row("id", "1")), childClosed), List.of("id"));

        projection.close();
        projection.close();

        assertTrue(childClosed.get());
        assertFalse(projection.hasNext());
        assertThrows(NoSuchElementException.class, projection::next);
    }

    @Test
    void emptyInputProducesNoRowsAndClosesChild() {
        AtomicBoolean childClosed = new AtomicBoolean();
        ProjectionOperator projection = new ProjectionOperator(
                new ListOperator(List.of(), childClosed), List.of("id"));

        assertFalse(projection.hasNext());
        assertTrue(childClosed.get());
        assertThrows(NoSuchElementException.class, projection::next);
    }

    @Test
    void closesChildWhenChildIterationFails() {
        AtomicBoolean childClosed = new AtomicBoolean();
        IllegalStateException expected = new IllegalStateException("child failed");
        Operator child = new ListOperator(List.of(), childClosed) {
            @Override
            public boolean hasNext() {
                throw expected;
            }
        };
        ProjectionOperator projection = new ProjectionOperator(child, List.of("id"));

        assertEquals(expected, assertThrows(IllegalStateException.class, projection::hasNext));
        assertTrue(childClosed.get());
    }

    private ProjectionOperator projection(List<Row> rows, List<String> columns) {
        return new ProjectionOperator(new ListOperator(rows), columns);
    }

    private List<Row> rows(Row... rows) {
        return List.of(rows);
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

    private Row rowWithNull(String nullColumn, String valueColumn, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(nullColumn, null);
        values.put(valueColumn, value);
        return new Row(values);
    }

    private static class ListOperator implements Operator {
        private final List<Row> rows;
        private final AtomicBoolean closed;
        private final AtomicInteger reads;
        private int index;

        private ListOperator(List<Row> rows) {
            this(rows, new AtomicBoolean(), new AtomicInteger());
        }

        private ListOperator(List<Row> rows, AtomicBoolean closed) {
            this(rows, closed, new AtomicInteger());
        }

        private ListOperator(List<Row> rows, AtomicBoolean closed, AtomicInteger reads) {
            this.rows = rows;
            this.closed = closed;
            this.reads = reads;
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
            reads.incrementAndGet();
            return rows.get(index++);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
