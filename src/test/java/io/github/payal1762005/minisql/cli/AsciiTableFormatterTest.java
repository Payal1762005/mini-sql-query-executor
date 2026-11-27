package io.github.payal1762005.minisql.cli;

import io.github.payal1762005.minisql.model.QueryResult;
import io.github.payal1762005.minisql.model.Row;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsciiTableFormatterTest {
    private final AsciiTableFormatter formatter = new AsciiTableFormatter();

    @Test
    void formatsHeadersRowsNullsAndLongValuesWithAlignedWidths() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", "Ava");
        values.put("department", "A very long department name");
        values.put("note", null);

        String table = formatter.format(new QueryResult(List.of(new Row(values))));

        assertEquals("+------+-----------------------------+------+\n"
                + "| name | department                  | note |\n"
                + "+------+-----------------------------+------+\n"
                + "| Ava  | A very long department name | NULL |\n"
                + "+------+-----------------------------+------+\n"
                + "(1 row)", table);
    }

    @Test
    void formatsEmptyAndZeroColumnResultsGracefully() {
        assertEquals("(0 rows)", formatter.format(new QueryResult(List.of())));
        assertEquals("(2 rows, no columns)", formatter.format(
                new QueryResult(List.of(new Row(Map.of()), new Row(Map.of())))));
    }

    @Test
    void escapesLineBreaksInsideValues() {
        String table = formatter.format(new QueryResult(List.of(
                new Row(Map.of("value", "first\nsecond")))));

        assertEquals("+---------------+\n"
                + "| value         |\n"
                + "+---------------+\n"
                + "| first\\nsecond |\n"
                + "+---------------+\n"
                + "(1 row)", table);
    }
}
