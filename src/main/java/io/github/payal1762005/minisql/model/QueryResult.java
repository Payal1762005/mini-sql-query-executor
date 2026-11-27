package io.github.payal1762005.minisql.model;

import java.util.List;

/**
 * The rows returned by a successfully executed query.
 */
public record QueryResult(List<Row> rows) {
    public QueryResult {
        rows = List.copyOf(rows);
    }
}
