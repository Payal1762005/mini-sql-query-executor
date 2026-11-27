package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateStateTest {
    @Test
    void countStarCountsEveryRowAcrossMergedStatesAndHandlesEmptyInput() {
        AggregateState left = state(AggregateFunction.COUNT, "*");
        AggregateState right = state(AggregateFunction.COUNT, "*");

        assertEquals("0", left.result());
        left.add(rowWithNull("amount"));
        right.add(row("amount", "not-a-number"));
        left.merge(right);

        assertEquals("2", left.result());
        assertEquals("0", state(AggregateFunction.COUNT, "*").result());
    }

    @Test
    void countColumnCountsOnlyNonNullValuesAcrossMergedStates() {
        AggregateState left = state(AggregateFunction.COUNT, "amount");
        AggregateState right = state(AggregateFunction.COUNT, "amount");

        left.add(rowWithNull("amount"));
        left.add(row("amount", "1"));
        right.add(row("amount", "2"));
        left.merge(right);

        assertEquals("2", left.result());
        assertEquals("0", state(AggregateFunction.COUNT, "amount").result());
    }

    @Test
    void countColumnRejectsMissingColumnButCountStarDoesNotInspectColumns() {
        assertThrows(IllegalArgumentException.class,
                () -> state(AggregateFunction.COUNT, "amount").add(row("other", "1")));

        AggregateState countAll = state(AggregateFunction.COUNT, "*");
        countAll.add(row("other", "1"));
        assertEquals("1", countAll.result());
    }

    @Test
    void sumMergesExactDecimalTotalsAndReturnsNullForEmptyOrAllNullValues() {
        AggregateState left = state(AggregateFunction.SUM, "amount");
        AggregateState right = state(AggregateFunction.SUM, "amount");
        left.add(row("amount", "0.10"));
        right.add(row("amount", "0.20"));
        left.merge(right);

        assertEquals("0.3", left.result());

        AggregateState empty = state(AggregateFunction.SUM, "amount");
        AggregateState nullOnly = state(AggregateFunction.SUM, "amount");
        nullOnly.add(rowWithNull("amount"));
        empty.merge(nullOnly);
        assertNull(empty.result());
    }

    @Test
    void averageMergesTotalsAndCountsThenRoundsOnceAtTheExistingScale() {
        AggregateState left = state(AggregateFunction.AVG, "amount");
        AggregateState right = state(AggregateFunction.AVG, "amount");
        left.add(row("amount", "1"));
        left.add(row("amount", "2"));
        right.add(row("amount", "2"));
        left.merge(right);

        assertEquals("1.6667", left.result());

        AggregateState halfUpLeft = state(AggregateFunction.AVG, "amount");
        AggregateState halfUpRight = state(AggregateFunction.AVG, "amount");
        halfUpLeft.add(row("amount", "0"));
        halfUpRight.add(row("amount", "2.4689"));
        halfUpLeft.merge(halfUpRight);
        assertEquals("1.2345", halfUpLeft.result());
    }

    @Test
    void averageReturnsNullForEmptyAndAllNullValues() {
        AggregateState empty = state(AggregateFunction.AVG, "amount");
        AggregateState nullOnly = state(AggregateFunction.AVG, "amount");
        nullOnly.add(rowWithNull("amount"));
        empty.merge(nullOnly);

        assertNull(empty.result());
    }

    @Test
    void minimumAndMaximumMergeNumericExtremaAndIgnoreNulls() {
        AggregateState minimumLeft = state(AggregateFunction.MIN, "amount");
        AggregateState minimumRight = state(AggregateFunction.MIN, "amount");
        AggregateState maximumLeft = state(AggregateFunction.MAX, "amount");
        AggregateState maximumRight = state(AggregateFunction.MAX, "amount");

        minimumLeft.add(rowWithNull("amount"));
        minimumRight.add(row("amount", "100"));
        minimumRight.add(row("amount", "20"));
        maximumLeft.add(row("amount", "4"));
        maximumRight.add(row("amount", "20"));
        minimumLeft.merge(minimumRight);
        maximumLeft.merge(maximumRight);

        assertEquals("20", minimumLeft.result());
        assertEquals("20", maximumLeft.result());
    }

    @Test
    void minimumAndMaximumReturnNullForEmptyAndAllNullValues() {
        for (AggregateFunction function : new AggregateFunction[]{
                AggregateFunction.MIN, AggregateFunction.MAX}) {
            AggregateState empty = state(function, "amount");
            AggregateState nullOnly = state(function, "amount");
            nullOnly.add(rowWithNull("amount"));
            empty.merge(nullOnly);

            assertNull(empty.result());
        }
    }

    @Test
    void allNumericStatesRejectInvalidValuesWithExistingMessageAndCause() {
        for (AggregateFunction function : new AggregateFunction[]{
                AggregateFunction.SUM, AggregateFunction.AVG,
                AggregateFunction.MIN, AggregateFunction.MAX}) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> state(function, "amount").add(row("amount", "bad-decimal")));

            assertTrue(exception.getMessage().contains("bad-decimal"));
            assertTrue(exception.getMessage().contains("amount"));
            assertTrue(exception.getCause() instanceof NumberFormatException);
        }
    }

    @Test
    void statesRejectMergingDifferentFunctionsOrColumns() {
        AggregateState sum = state(AggregateFunction.SUM, "amount");
        AggregateState average = state(AggregateFunction.AVG, "amount");
        AggregateState otherColumn = state(AggregateFunction.SUM, "other");

        assertThrows(IllegalArgumentException.class, () -> sum.merge(average));
        assertThrows(IllegalArgumentException.class, () -> sum.merge(otherColumn));
        assertThrows(IllegalArgumentException.class, () -> sum.merge(null));
    }

    private AggregateState state(AggregateFunction function, String inputColumn) {
        return AggregateStateFactory.create(
                new AggregateSpecification(function, inputColumn, "result"));
    }

    private Row row(String column, String value) {
        return new Row(Map.of(column, value));
    }

    private Row rowWithNull(String column) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(column, null);
        return new Row(values);
    }
}
