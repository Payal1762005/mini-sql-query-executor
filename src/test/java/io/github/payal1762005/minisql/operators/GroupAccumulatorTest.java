package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroupAccumulatorTest {
    @Test
    void accumulatesRepeatedRowsForMultipleGroupingColumnsAndAllFunctions() {
        List<AggregateSpecification> specifications = List.of(
                AggregateSpecification.countAll("rows"),
                spec(AggregateFunction.COUNT, "amount", "non_null"),
                spec(AggregateFunction.SUM, "amount", "total"),
                spec(AggregateFunction.AVG, "amount", "average"),
                spec(AggregateFunction.MIN, "amount", "minimum"),
                spec(AggregateFunction.MAX, "amount", "maximum"));
        GroupAccumulator group = new GroupAccumulator(
                List.of("department", "region"), List.of("OrbitOps", "East"), specifications);

        group.add(row("department", "OrbitOps", "region", "East", "amount", "0.10"));
        group.add(row("department", "OrbitOps", "region", "East", "amount", "0.20"));

        assertEquals(new Row(Map.of(
                "department", "OrbitOps",
                "region", "East",
                "rows", "2",
                "non_null", "2",
                "total", "0.3",
                "average", "0.1500",
                "minimum", "0.1",
                "maximum", "0.2")), group.result());
    }

    @Test
    void supportsNullGroupKeysAndReturnsAnImmutableKey() {
        List<String> keyValues = new ArrayList<>();
        keyValues.add(null);
        keyValues.add("East");
        GroupAccumulator group = new GroupAccumulator(
                List.of("department", "region"), keyValues,
                List.of(AggregateSpecification.countAll("count")));
        group.add(rowWithNullableGroup(
                "department", null, "region", "East", "amount", null));
        keyValues.set(0, "mutated");

        assertEquals(new Row(values(
                "department", null, "region", "East", "count", "1")), group.result());
        assertEquals(java.util.Arrays.asList(null, "East"), group.key());
        assertThrows(UnsupportedOperationException.class, () -> group.key().set(0, "changed"));
    }

    @Test
    void mergeCombinesOnlyCompatibleKeysAndSpecifications() {
        List<String> groups = List.of("department");
        List<AggregateSpecification> specs = List.of(
                spec(AggregateFunction.SUM, "amount", "total"));
        GroupAccumulator left = new GroupAccumulator(groups, List.of("Sales"), specs);
        GroupAccumulator right = new GroupAccumulator(groups, List.of("Sales"), specs);
        left.add(row("department", "Sales", "amount", "0.1"));
        right.add(row("department", "Sales", "amount", "0.2"));
        left.merge(right);

        assertEquals("0.3", left.result().values().get("total"));
        assertEquals("0.2", right.result().values().get("total"));

        assertThrows(IllegalArgumentException.class,
                () -> left.merge(new GroupAccumulator(groups, List.of("Engineering"), specs)));
        assertThrows(IllegalArgumentException.class,
                () -> left.merge(new GroupAccumulator(groups, List.of("Sales"),
                        List.of(spec(AggregateFunction.MAX, "amount", "total")))));
    }

    @Test
    void rejectsRowsThatDoNotMatchGroupKeyOrOmitAGroupColumn() {
        GroupAccumulator group = new GroupAccumulator(
                List.of("department"), List.of("Sales"),
                List.of(AggregateSpecification.countAll("count")));

        assertThrows(IllegalArgumentException.class,
                () -> group.add(row("department", "Engineering", "amount", "1")));
        assertThrows(IllegalArgumentException.class, () -> group.add(row("amount", "1")));
    }

    private AggregateSpecification spec(AggregateFunction function, String input, String output) {
        return new AggregateSpecification(function, input, output);
    }

    private Row row(String... entries) {
        return new Row(values(entries));
    }

    private Row rowWithNullableGroup(String... entries) {
        return new Row(values(entries));
    }

    private Map<String, String> values(String... entries) {
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
