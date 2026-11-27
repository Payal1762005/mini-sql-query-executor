package io.github.payal1762005.minisql.engine;

import io.github.payal1762005.minisql.exception.SqlExecutionException;
import io.github.payal1762005.minisql.exception.SqlParseException;
import io.github.payal1762005.minisql.model.QueryResult;

/**
 * Coordinates parsing, planning, and execution of a SQL query.
 */
public interface QueryEngine {
    QueryResult execute(String sql) throws SqlParseException, SqlExecutionException;
}
