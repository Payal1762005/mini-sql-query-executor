package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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

class AggregateOperatorTest {
    @Test
    void countStarCountsEveryInputRow() {
        Row result = aggregate(rows(
                row("amount", "1"), row("amount", "invalid")),
                List.of(),
                AggregateSpecification.countAll("row_count")).next();

        assertEquals("2", result.values().get("row_count"));
    }

    @Test
    void sumsDecimalValuesWithoutFloatingPointArithmetic() {
        Row result = aggregate(rows(row("amount", "0.10"), row("amount", "0.20")),
                List.of(), spec(AggregateFunction.SUM, "amount", "total")).next();

        assertEquals("0.3", result.values().get("total"));
    }

    @Test
    void averagesUsingDocumentedScaleAndRounding() {
        Row result = aggregate(rows(row("amount", "1"), row("amount", "2")),
                List.of(), spec(AggregateFunction.AVG, "amount", "average")).next();

        assertEquals("1.5000", result.values().get("average"));
        assertEquals(4, AggregateOperator.AVG_SCALE);
    }

    @Test
    void averageUsesHalfUpRoundingAtFourDecimalPlaces() {
        Row result = aggregate(rows(row("amount", "0"), row("amount", "2.4689")),
                List.of(), spec(AggregateFunction.AVG, "amount", "average")).next();

        assertEquals("1.2345", result.values().get("average"));
        assertEquals(java.math.RoundingMode.HALF_UP, AggregateOperator.AVG_ROUNDING_MODE);
    }

    @Test
    void computesNumericMinimumAndMaximum() {
        Row result = aggregate(rows(row("amount", "100"), row("amount", "20")),
                List.of(),
                spec(AggregateFunction.MIN, "amount", "minimum"),
                spec(AggregateFunction.MAX, "amount", "maximum")).next();

        assertEquals("20", result.values().get("minimum"));
        assertEquals("100", result.values().get("maximum"));
    }

    @Test
    void calculatesMultipleAggregatesInOnePass() {
        Row result = aggregate(rows(row("amount", "2"), row("amount", "4")),
                List.of(),
                AggregateSpecification.countAll("count"),
                spec(AggregateFunction.SUM, "amount", "sum"),
                spec(AggregateFunction.AVG, "amount", "avg"),
                spec(AggregateFunction.MIN, "amount", "min"),
                spec(AggregateFunction.MAX, "amount", "max")).next();

        assertEquals(Map.of("count", "2", "sum", "6", "avg", "3.0000", "min", "2", "max", "4"),
                result.values());
    }

    @Test
    void groupsByOneColumnInFirstSeenOrder() {
        AggregateOperator aggregate = aggregate(rows(
                        row("department", "Sales", "amount", "5"),
                        row("department", "Engineering", "amount", "2"),
                        row("department", "Sales", "amount", "7")),
                List.of("department"), spec(AggregateFunction.SUM, "amount", "total"));

        assertEquals(List.of(
                row("department", "Sales", "total", "12"),
                row("department", "Engineering", "total", "2")),
                consume(aggregate));
    }

    @Test
    void groupsByMultipleColumns() {
        AggregateOperator aggregate = aggregate(rows(
                        row("department", "Sales", "region", "East", "amount", "3"),
                        row("department", "Sales", "region", "West", "amount", "4"),
                        row("department", "Sales", "region", "East", "amount", "5")),
                List.of("department", "region"),
                spec(AggregateFunction.SUM, "amount", "total"));

        assertEquals(List.of(
                row("department", "Sales", "region", "East", "total", "8"),
                row("department", "Sales", "region", "West", "total", "4")),
                consume(aggregate));
    }

    @Test
    void globalAggregationProducesOneRowAndHandlesEmptyInput() {
        Row result = aggregate(List.of(), List.of(),
                AggregateSpecification.countAll("count"),
                spec(AggregateFunction.SUM, "amount", "sum"),
                spec(AggregateFunction.AVG, "amount", "avg"),
                spec(AggregateFunction.MIN, "amount", "min"),
                spec(AggregateFunction.MAX, "amount", "max")).next();

        assertEquals("0", result.values().get("count"));
        assertNull(result.values().get("sum"));
        assertNull(result.values().get("avg"));
        assertNull(result.values().get("min"));
        assertNull(result.values().get("max"));
    }

    @Test
    void emptyGroupedInputProducesNoGroups() {
        AggregateOperator aggregate = aggregate(List.of(), List.of("department"),
                AggregateSpecification.countAll("count"));
        assertEquals(List.of(), consume(aggregate));
    }

    @Test
    void ignoresNullInputsAndCountsOnlyNonNullValuesForCountColumn() {
        Row result = aggregate(rows(rowWithNull("amount"), row("amount", "5")),
                List.of(),
                spec(AggregateFunction.COUNT, "amount", "count"),
                spec(AggregateFunction.SUM, "amount", "sum")).next();

        assertEquals("1", result.values().get("count"));
        assertEquals("5", result.values().get("sum"));
    }

    @Test
    void avgMinAndMaxIgnoreNullInputs() {
        Row result = aggregate(rows(
                        rowWithNull("amount"),
                        row("amount", "2"),
                        rowWithNull("amount"),
                        row("amount", "4")),
                List.of(),
                spec(AggregateFunction.AVG, "amount", "average"),
                spec(AggregateFunction.MIN, "amount", "minimum"),
                spec(AggregateFunction.MAX, "amount", "maximum")).next();

        assertEquals("3.0000", result.values().get("average"));
        assertEquals("2", result.values().get("minimum"));
        assertEquals("4", result.values().get("maximum"));
    }

    @Test
    void nullGroupKeysCreateAndReuseOneGroup() {
        AggregateOperator aggregate = aggregate(rows(
                        rowWithNullGroupAndAmount(null, "2"),
                        rowWithNullGroupAndAmount("Sales", "3"),
                        rowWithNullGroupAndAmount(null, "4")),
                List.of("department"),
                AggregateSpecification.countAll("count"),
                spec(AggregateFunction.SUM, "amount", "total"));

        assertEquals(List.of(
                rowWithNullableGroup("department", null, "count", "2", "total", "6"),
                row("department", "Sales", "count", "1", "total", "3")),
                consume(aggregate));
    }

    @Test
    void rejectsInvalidNumericValuesWithColumnAndValueInMessage() {
        AggregateOperator aggregate = aggregate(rows(row("amount", "not-a-number")),
                List.of(), spec(AggregateFunction.SUM, "amount", "sum"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, aggregate::hasNext);
        assertTrue(exception.getMessage().contains("amount"));
        assertTrue(exception.getMessage().contains("not-a-number"));
    }

    @Test
    void avgMinAndMaxRejectInvalidNumericValuesMeaningfully() {
        for (AggregateFunction function : List.of(
                AggregateFunction.AVG, AggregateFunction.MIN, AggregateFunction.MAX)) {
            AggregateOperator aggregate = aggregate(rows(row("amount", "not-a-number")),
                    List.of(), spec(function, "amount", function.name().toLowerCase()));

            IllegalArgumentException exception =
                    assertThrows(IllegalArgumentException.class, aggregate::hasNext);
            assertTrue(exception.getMessage().contains("amount"));
            assertTrue(exception.getMessage().contains("not-a-number"));
        }
    }

    @Test
    void rejectsMissingAggregateAndGroupColumns() {
        AggregateOperator missingAggregate = aggregate(rows(row("department", "Sales")),
                List.of(), spec(AggregateFunction.SUM, "amount", "total"));
        assertTrue(assertThrows(IllegalArgumentException.class, missingAggregate::hasNext)
                .getMessage().contains("amount"));

        AggregateOperator missingGroup = aggregate(rows(row("amount", "1")),
                List.of("department"), AggregateSpecification.countAll("count"));
        assertTrue(assertThrows(IllegalArgumentException.class, missingGroup::hasNext)
                .getMessage().contains("department"));
    }

    @Test
    void rejectsDuplicateGroupColumnsAndAggregateOutputNames() {
        ListOperator child = new ListOperator(List.of());
        assertThrows(IllegalArgumentException.class,
                () -> new AggregateOperator(child, List.of("department", "department"),
                        AggregateSpecification.countAll("count")));

        assertThrows(IllegalArgumentException.class,
                () -> new AggregateOperator(new ListOperator(List.of()), List.of(),
                        AggregateSpecification.countAll("count"),
                        spec(AggregateFunction.SUM, "amount", "count")));
    }

    @Test
    void rejectsAggregateOutputNameCollidingWithGroupColumn() {
        assertThrows(IllegalArgumentException.class,
                () -> new AggregateOperator(new ListOperator(List.of()), List.of("department"),
                        AggregateSpecification.countAll("department")));
    }

    @Test
    void consumesOnlyWhenRequestedAndClosesChildAfterAggregationAndOnClose() {
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean childClosed = new AtomicBoolean();
        Operator child = new ListOperator(rows(row("amount", "1"), row("amount", "2")),
                childClosed, reads);
        AggregateOperator aggregate = new AggregateOperator(child, List.of(),
                spec(AggregateFunction.SUM, "amount", "total"));

        assertEquals(0, reads.get());
        assertTrue(aggregate.hasNext());
        assertEquals(2, reads.get());
        assertTrue(childClosed.get());
        aggregate.close();
        aggregate.close();
        assertFalse(aggregate.hasNext());
    }

    @Test
    void explicitCloseBeforeAggregationStartsClosesChildAndNextFails() {
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean childClosed = new AtomicBoolean();
        AggregateOperator aggregate = new AggregateOperator(
                new ListOperator(rows(row("amount", "1")), childClosed, reads),
                List.of(), spec(AggregateFunction.SUM, "amount", "total"));

        aggregate.close();

        assertTrue(childClosed.get());
        assertEquals(0, reads.get());
        assertFalse(aggregate.hasNext());
        assertThrows(NoSuchElementException.class, aggregate::next);
    }

    @Test
    void repeatedHasNextDoesNotConsumeChildMoreThanOnce() {
        AtomicInteger reads = new AtomicInteger();
        AggregateOperator aggregate = new AggregateOperator(
                new ListOperator(rows(row("amount", "1"), row("amount", "2")),
                        new AtomicBoolean(), reads),
                List.of(), spec(AggregateFunction.SUM, "amount", "total"));

        assertTrue(aggregate.hasNext());
        assertEquals(2, reads.get());
        assertTrue(aggregate.hasNext());
        assertTrue(aggregate.hasNext());
        assertEquals(2, reads.get());
    }

    @Test
    void nextAfterExhaustionThrowsNoSuchElementException() {
        AggregateOperator aggregate = aggregate(List.of(), List.of("department"),
                AggregateSpecification.countAll("count"));

        assertFalse(aggregate.hasNext());
        assertThrows(NoSuchElementException.class, aggregate::next);
    }

    @Test
    void closesChildWhenAggregationFails() {
        AtomicBoolean childClosed = new AtomicBoolean();
        AggregateOperator aggregate = new AggregateOperator(
                new ListOperator(rows(row("amount", "bad")), childClosed, new AtomicInteger()),
                List.of(), spec(AggregateFunction.AVG, "amount", "avg"));

        assertThrows(IllegalArgumentException.class, aggregate::hasNext);
        assertTrue(childClosed.get());
    }

    private AggregateOperator aggregate(List<Row> rows, List<String> groups,
                                        AggregateSpecification... specifications) {
        return new AggregateOperator(new ListOperator(rows), groups, List.of(specifications));
    }

    private AggregateSpecification spec(AggregateFunction function, String input, String output) {
        return new AggregateSpecification(function, input, output);
    }

    private List<Row> consume(Operator operator) {
        List<Row> results = new ArrayList<>();
        try (operator) {
            while (operator.hasNext()) {
                results.add(operator.next());
            }
        }
        return results;
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

    private Row rowWithNull(String key) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, null);
        return new Row(values);
    }

    private Row rowWithNullGroupAndAmount(String department, String amount) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("department", department);
        values.put("amount", amount);
        return new Row(values);
    }

    private Row rowWithNullableGroup(String groupKey, String groupValue,
                                     String countKey, String countValue,
                                     String totalKey, String totalValue) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(groupKey, groupValue);
        values.put(countKey, countValue);
        values.put(totalKey, totalValue);
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
