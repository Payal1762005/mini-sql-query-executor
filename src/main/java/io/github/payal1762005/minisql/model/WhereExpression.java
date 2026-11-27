package io.github.payal1762005.minisql.model;

/**
 * A boolean expression supported in a WHERE clause.
 */
public sealed interface WhereExpression permits ComparisonExpression, LogicalExpression {
}
