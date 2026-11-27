package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PartialAggregationTest {
    @Test
    void aggregatesRepeatedAndMultipleColumnGroupsInFirstSeenOrder() {
        PartialAggregation partial = partial(List.of(
                        row("department", "Sales", "region", "East", "amount", "1"),
                        row("department", "Sales", "region", "West", "amount", "2"),
                        row("department", "Sales", "region", "East", "amount", "3")),
                List.of("department", "region"),
                List.of(spec(AggregateFunction.SUM, "amount", "total")));

        assertEquals(List.of(
                        row("department", "Sales", "region", "East", "total", "4"),
                        row("department", "Sales", "region", "West", "total", "2")),
                partial.resultRows());
    }

    @Test
    void groupsRowsWithNullKeysAndSupportsAggregateFunctionSet() {
        PartialAggregation partial = partial(List.of(
                        rowWithNullDepartment("amount", "0.10"),
                        rowWithNullDepartment("amount", "0.20")),
                List.of("department"),
                List.of(
                        AggregateSpecification.countAll("rows"),
                        spec(AggregateFunction.COUNT, "amount", "non_null"),
                        spec(AggregateFunction.SUM, "amount", "total"),
                        spec(AggregateFunction.AVG, "amount", "average"),
                        spec(AggregateFunction.MIN, "amount", "minimum"),
                        spec(AggregateFunction.MAX, "amount", "maximum")));

        assertEquals(List.of(new Row(nullableValues(
                "department", null, "rows", "2", "non_null", "2",
                "total", "0.3", "average", "0.1500", "minimum", "0.1", "maximum", "0.2"))),
                partial.resultRows());
    }

    @Test
    void globalAggregationCreatesAResultForEmptyInput() {
        PartialAggregation partial = partial(List.of(), List.of(), List.of(
                AggregateSpecification.countAll("rows"),
                spec(AggregateFunction.COUNT, "amount", "non_null"),
                spec(AggregateFunction.SUM, "amount", "total"),
                spec(AggregateFunction.AVG, "amount", "average"),
                spec(AggregateFunction.MIN, "amount", "minimum"),
                spec(AggregateFunction.MAX, "amount", "maximum")));

        Row result = partial.resultRows().get(0);
        assertEquals("0", result.values().get("rows"));
        assertEquals("0", result.values().get("non_null"));
        assertNull(result.values().get("total"));
        assertNull(result.values().get("average"));
        assertNull(result.values().get("minimum"));
        assertNull(result.values().get("maximum"));
    }

    @Test
    void emptyGroupedAggregationProducesNoGroups() {
        PartialAggregation partial = partial(List.of(), List.of("department"),
                List.of(AggregateSpecification.countAll("rows")));

        assertEquals(List.of(), partial.resultRows());
    }

    @Test
    void mergingPartialsCombinesOverlapsAndAppendsNewGroupsInSourceOrder() {
        List<String> groups = List.of("department");
        List<AggregateSpecification> specs = List.of(
                AggregateSpecification.countAll("rows"),
                spec(AggregateFunction.SUM, "amount", "total"));
        PartialAggregation destination = partial(List.of(
                row("department", "Sales", "amount", "0.1"),
                row("department", "Engineering", "amount", "2")),
                groups, specs);
        PartialAggregation source = partial(List.of(
                row("department", "Engineering", "amount", "3"),
                row("department", "Sales", "amount", "0.2"),
                row("department", "Support", "amount", "4")),
                groups, specs);

        destination.merge(source);

        assertEquals(List.of(
                        row("department", "Sales", "rows", "2", "total", "0.3"),
                        row("department", "Engineering", "rows", "2", "total", "5"),
                        row("department", "Support", "rows", "1", "total", "4")),
                destination.resultRows());
        assertEquals(List.of(
                        row("department", "Engineering", "rows", "1", "total", "3"),
                        row("department", "Sales", "rows", "1", "total", "0.2"),
                        row("department", "Support", "rows", "1", "total", "4")),
                source.resultRows());
    }

    @Test
    void mergingGlobalPartialsRetainsOneGroupAndRoundsAverageAfterMerge() {
        List<AggregateSpecification> specs = List.of(
                AggregateSpecification.countAll("rows"),
                spec(AggregateFunction.AVG, "amount", "average"));
        PartialAggregation left = partial(List.of(
                row("amount", "0"), row("amount", "2")), List.of(), specs);
        PartialAggregation right = partial(List.of(row("amount", "2.4689")), List.of(), specs);

        left.merge(right);

        assertEquals(List.of(row("rows", "3", "average", "1.4896")), left.resultRows());
        assertEquals(List.of(row("rows", "1", "average", "2.4689")), right.resultRows());
    }

    @Test
    void rejectsIncompatibleGroupColumnsAndAggregateSpecifications() {
        PartialAggregation base = partial(List.of(), List.of("department"),
                List.of(spec(AggregateFunction.SUM, "amount", "total")));
        PartialAggregation differentGroups = partial(List.of(), List.of("region"),
                List.of(spec(AggregateFunction.SUM, "amount", "total")));
        PartialAggregation differentSpecs = partial(List.of(), List.of("department"),
                List.of(spec(AggregateFunction.MAX, "amount", "total")));

        assertThrows(IllegalArgumentException.class, () -> base.merge(differentGroups));
        assertThrows(IllegalArgumentException.class, () -> base.merge(differentSpecs));
    }

    @Test
    void constructorCopiesInputCollectionAndConfiguration() {
        List<String> groupColumns = new ArrayList<>(List.of("department"));
        List<AggregateSpecification> specs = new ArrayList<>(
                List.of(AggregateSpecification.countAll("rows")));
        List<Row> rows = new ArrayList<>(
                List.of(row("department", "Sales", "amount", "1")));
        PartialAggregation partial = new PartialAggregation(rows, groupColumns, specs);
        rows.clear();
        groupColumns.clear();
        specs.clear();

        assertEquals(List.of(row("department", "Sales", "rows", "1")), partial.resultRows());
    }

    private PartialAggregation partial(
            List<Row> rows, List<String> groupColumns, List<AggregateSpecification> specs) {
        return new PartialAggregation(rows, groupColumns, specs);
    }

    private AggregateSpecification spec(AggregateFunction function, String input, String output) {
        return new AggregateSpecification(function, input, output);
    }

    private Row row(String... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Expected key/value pairs");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put(entries[index], entries[index + 1]);
        }
        return new Row(values);
    }

    private Row rowWithNullDepartment(String key, String value) {
        return new Row(nullableValues("department", null, key, value));
    }

    private Map<String, String> nullableValues(String... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Expected key/value pairs");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put(entries[index], entries[index + 1]);
        }
        return values;
    }
}
