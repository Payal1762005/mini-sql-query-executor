package io.github.payal1762005.minisql.parser;

import io.github.payal1762005.minisql.exception.SqlParseException;
import io.github.payal1762005.minisql.model.QueryPlan;

/**
 * Converts SQL text into a logical query plan.
 */
public interface SqlParser {
    QueryPlan parse(String sql) throws SqlParseException;
}
