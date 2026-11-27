package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComparisonPredicateTest {
    @Test
    void supportsAllSixComparisonOperatorsForNumbers() {
        Row row = row("value", "10");

        assertTrue(predicate(ComparisonOperator.EQUALS, 10).test(row));
        assertFalse(predicate(ComparisonOperator.NOT_EQUALS, 10).test(row));
        assertTrue(predicate(ComparisonOperator.GREATER_THAN, 9).test(row));
        assertTrue(predicate(ComparisonOperator.LESS_THAN, 11).test(row));
        assertTrue(predicate(ComparisonOperator.GREATER_THAN_OR_EQUAL, 10).test(row));
        assertTrue(predicate(ComparisonOperator.LESS_THAN_OR_EQUAL, 10).test(row));
    }

    @Test
    void comparesIntegerTextNumericallyRatherThanLexicographically() {
        assertTrue(predicate("salary", ComparisonOperator.GREATER_THAN, 20).test(row("salary", "100")));
        assertFalse(predicate("salary", ComparisonOperator.LESS_THAN, 20).test(row("salary", "100")));
    }

    @Test
    void comparesDecimalValuesNumerically() {
        assertTrue(predicate("amount", ComparisonOperator.GREATER_THAN, new BigDecimal("10.05"))
                .test(row("amount", "10.5")));
        assertTrue(predicate("amount", ComparisonOperator.EQUALS, new BigDecimal("10.500"))
                .test(row("amount", "10.5")));
        assertTrue(predicate("amount", ComparisonOperator.LESS_THAN_OR_EQUAL, new BigDecimal("10.5"))
                .test(row("amount", "10.50")));
    }

    @Test
    void comparesStringsForEqualityAndOrdering() {
        assertTrue(predicate("department", ComparisonOperator.EQUALS, "Engineering")
                .test(row("department", "Engineering")));
        assertTrue(predicate("department", ComparisonOperator.NOT_EQUALS, "Engineering")
                .test(row("department", "Sales")));
        assertTrue(predicate("department", ComparisonOperator.GREATER_THAN, "M")
                .test(row("department", "Sales")));
        assertTrue(predicate("department", ComparisonOperator.LESS_THAN, "T")
                .test(row("department", "Sales")));
        assertTrue(predicate("department", ComparisonOperator.GREATER_THAN_OR_EQUAL, "Sales")
                .test(row("department", "Sales")));
        assertTrue(predicate("department", ComparisonOperator.LESS_THAN_OR_EQUAL, "Sales")
                .test(row("department", "Sales")));
    }

    @Test
    void rejectsMissingColumnsWithColumnNameInMessage() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> predicate("department", ComparisonOperator.EQUALS, "Ava")
                        .test(row("name", "Ava")));

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("department"));
    }

    @Test
    void rejectsNullExpectedValueAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonPredicate("name", ComparisonOperator.EQUALS, null));
    }

    @Test
    void rowModelPreservesNullValuesImmutably() {
        Map<String, String> values = new java.util.HashMap<>();
        values.put("name", null);
        Row row = new Row(values);
        values.put("name", "Ava");
        assertTrue(row.values().containsKey("name"));
        org.junit.jupiter.api.Assertions.assertNull(row.values().get("name"));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonPredicate("name", ComparisonOperator.EQUALS, "Ava").test(row));
    }

    @Test
    void rejectsInvalidNumericColumnValuesMeaningfully() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> predicate("salary", ComparisonOperator.GREATER_THAN, 20)
                        .test(row("salary", "unknown")));

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("salary"));
        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("unknown"));
    }

    @Test
    void handlesBoundaryEqualityForIntegerAndDecimalValues() {
        assertTrue(predicate(ComparisonOperator.EQUALS, 20).test(row("value", "20")));
        assertTrue(predicate(ComparisonOperator.NOT_EQUALS, 21).test(row("value", "20")));
        assertTrue(predicate(ComparisonOperator.GREATER_THAN_OR_EQUAL, new BigDecimal("20.0"))
                .test(row("value", "20")));
        assertTrue(predicate(ComparisonOperator.LESS_THAN_OR_EQUAL, new BigDecimal("20.00"))
                .test(row("value", "20.0")));
    }

    private ComparisonPredicate predicate(ComparisonOperator operator, Object expected) {
        return predicate("value", operator, expected);
    }

    private ComparisonPredicate predicate(String column, ComparisonOperator operator, Object expected) {
        return new ComparisonPredicate(column, operator, expected);
    }

    private Row row(String column, String value) {
        return new Row(Map.of(column, value));
    }
}
