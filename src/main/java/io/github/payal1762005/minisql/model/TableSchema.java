package io.github.payal1762005.minisql.model;

import java.util.List;

/**
 * The name and ordered columns that describe a table.
 */
public record TableSchema(String tableName, List<String> columns) {
    public TableSchema {
        columns = List.copyOf(columns);
    }
}
