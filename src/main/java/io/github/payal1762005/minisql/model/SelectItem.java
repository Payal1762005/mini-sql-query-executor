package io.github.payal1762005.minisql.model;

/**
 * An item in a SELECT list.
 */
public sealed interface SelectItem permits AllColumns, ColumnReference, AggregateExpression {
}
