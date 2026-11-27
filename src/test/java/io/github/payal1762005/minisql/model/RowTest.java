package io.github.payal1762005.minisql.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RowTest {
    @Test
    void copiesValuesToKeepRowImmutable() {
        Map<String, String> source = new HashMap<>();
        source.put("department", "Engineering");

        Row row = new Row(source);
        source.put("department", "Sales");

        assertEquals("Engineering", row.values().get("department"));
    }

    @Test
    void valuesCannotBeModifiedDirectly() {
        Row row = new Row(Map.of("department", "Engineering"));

        assertThrows(UnsupportedOperationException.class,
                () -> row.values().put("department", "Sales"));
    }

    @Test
    void supportsNullValuesAndCopiesThemFromSourceMap() {
        Map<String, String> source = new HashMap<>();
        source.put("department", null);

        Row row = new Row(source);
        source.put("department", "Sales");

        assertNull(row.values().get("department"));
        assertThrows(UnsupportedOperationException.class,
                () -> row.values().put("department", "Engineering"));
    }
}
