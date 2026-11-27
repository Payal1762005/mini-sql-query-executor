package io.github.payal1762005.minisql.planner;

import io.github.payal1762005.minisql.exception.SqlExecutionException;
import io.github.payal1762005.minisql.model.AllColumns;
import io.github.payal1762005.minisql.model.AggregateExpression;
import io.github.payal1762005.minisql.model.ColumnReference;
import io.github.payal1762005.minisql.model.ComparisonExpression;
import io.github.payal1762005.minisql.model.LiteralValue;
import io.github.payal1762005.minisql.model.LogicalExpression;
import io.github.payal1762005.minisql.model.LogicalOperator;
import io.github.payal1762005.minisql.model.LogicalPlanNode;
import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.model.SelectItem;
import io.github.payal1762005.minisql.model.SelectQuery;
import io.github.payal1762005.minisql.model.TableSchema;
import io.github.payal1762005.minisql.model.WhereExpression;
import io.github.payal1762005.minisql.operators.AggregateOperator;
import io.github.payal1762005.minisql.operators.AggregateSpecification;
import io.github.payal1762005.minisql.operators.AggregationExecutionConfig;
import io.github.payal1762005.minisql.operators.ComparisonOperator;
import io.github.payal1762005.minisql.operators.ComparisonPredicate;
import io.github.payal1762005.minisql.operators.FilterOperator;
import io.github.payal1762005.minisql.operators.Operator;
import io.github.payal1762005.minisql.operators.AggregationOperatorFactory;
import io.github.payal1762005.minisql.operators.ProjectionOperator;
import io.github.payal1762005.minisql.operators.ScanOperator;
import io.github.payal1762005.minisql.storage.ScanPredicate;
import io.github.payal1762005.minisql.storage.TableStorage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Converts a parsed SELECT AST into an executable operator pipeline.
 *
 * <p>Table names, column names, and output aliases use exact, case-sensitive
 * matching. SQL keywords remain case-insensitive in the tokenizer.</p>
 */
public final class LogicalPlanBuilder {
    private final TableStorage storage;
    private final AggregationExecutionConfig aggregationConfig;

    public LogicalPlanBuilder(TableStorage storage) {
        this(storage, AggregationExecutionConfig.serial());
    }

    public LogicalPlanBuilder(
            TableStorage storage, AggregationExecutionConfig aggregationConfig) {
        this.storage = Objects.requireNonNull(storage, "Table storage must not be null");
        this.aggregationConfig = Objects.requireNonNull(
                aggregationConfig, "Aggregation execution configuration must not be null");
    }

    public Operator build(SelectQuery query) throws SqlExecutionException {
        Objects.requireNonNull(query, "SELECT query must not be null");

        TableSchema schema = getSchema(query.tableName());
        validate(query, schema);
        List<ScanPredicate> pushedPredicates = pushedPredicates(query.where().orElse(null));

        Operator operator;
        try {
            operator = new ScanOperator(storage, query.tableName(),
                    requiredColumns(query, schema), pushedPredicates);
        } catch (IOException exception) {
            throw new SqlExecutionException(
                    "Unable to scan table '" + query.tableName() + "'", exception);
        }

        try {
            return buildPipeline(query, schema, operator, pushedPredicates.isEmpty());
        } catch (RuntimeException | Error failure) {
            try {
                operator.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * Builds a descriptive logical plan without opening a scan or consuming rows.
     */
    public LogicalPlanNode explain(SelectQuery query) throws SqlExecutionException {
        Objects.requireNonNull(query, "SELECT query must not be null");
        TableSchema schema = getSchema(query.tableName());
        validate(query, schema);

        List<ScanPredicate> pushedPredicates = pushedPredicates(query.where().orElse(null));
        String scanDetails = "table=" + query.tableName() + ", columns=["
                + String.join(", ", requiredColumns(query, schema)) + "]";
        if (!pushedPredicates.isEmpty()) {
            scanDetails += ", pushed=[" + formatPushedPredicates(pushedPredicates) + "]";
        }
        LogicalPlanNode node = new LogicalPlanNode("Scan",
                scanDetails, List.of());
        if (query.where().isPresent() && pushedPredicates.isEmpty()) {
            node = new LogicalPlanNode("Filter",
                    "residual: " + formatWhere(query.where().orElseThrow()), List.of(node));
        }

        if (containsAggregate(query)) {
            String functions = query.selectItems().stream()
                    .filter(AggregateExpression.class::isInstance)
                    .map(AggregateExpression.class::cast)
                    .map(this::aggregateLabel)
                    .reduce((left, right) -> left + ", " + right)
                    .orElseThrow();
            String grouping = query.groupByColumns().isEmpty()
                    ? "global"
                    : String.join(", ", query.groupByColumns());
            String aggregateDetails = "functions=[" + functions + "], GROUP BY [" + grouping + "]";
            if (aggregationConfig.parallelEnabled()) {
                aggregateDetails += ", workers=" + aggregationConfig.workerCount()
                        + ", batchSize=" + aggregationConfig.batchSize()
                        + ", maxInFlightBatches=" + aggregationConfig.workerCount();
            }
            node = new LogicalPlanNode(
                    aggregationConfig.parallelEnabled() ? "ParallelAggregate" : "Aggregate",
                    aggregateDetails, List.of(node));
        }

        List<String> selected = new ArrayList<>();
        for (SelectItem item : query.selectItems()) {
            if (item instanceof AllColumns) {
                selected.addAll(schema.columns());
            } else if (item instanceof ColumnReference column) {
                selected.add(column.alias()
                        .map(alias -> column.columnName() + " AS " + alias)
                        .orElse(column.columnName()));
            } else if (item instanceof AggregateExpression aggregate) {
                selected.add(aggregate.alias()
                        .map(alias -> aggregateLabel(aggregate) + " AS " + alias)
                        .orElseGet(() -> aggregateLabel(aggregate)));
            }
        }
        return new LogicalPlanNode("Projection", String.join(", ", selected), List.of(node));
    }

    private void validate(SelectQuery query, TableSchema schema) throws SqlExecutionException {
        if (!query.tableName().equals(schema.tableName())) {
            throw new SqlExecutionException("Table name '" + query.tableName()
                    + "' does not match the case-sensitive table name '" + schema.tableName() + "'");
        }
        validateQuery(query, schema);
    }

    private Operator buildPipeline(
            SelectQuery query, TableSchema schema, Operator operator, boolean filterIsResidual) {
        if (query.where().isPresent() && filterIsResidual) {
            operator = new FilterOperator(operator, predicate(query.where().orElseThrow()));
        }

        if (containsAggregate(query)) {
            List<AggregateSpecification> specifications = new ArrayList<>();
            List<String> projectionSources = new ArrayList<>();
            List<String> projectionOutputs = new ArrayList<>();
            for (SelectItem item : query.selectItems()) {
                if (item instanceof ColumnReference column) {
                    projectionSources.add(column.columnName());
                    projectionOutputs.add(column.alias().orElse(column.columnName()));
                } else if (item instanceof AggregateExpression aggregate) {
                    String outputName = aggregateOutputName(aggregate);
                    specifications.add(new AggregateSpecification(
                            aggregate.function(), aggregate.columnName(), outputName));
                    projectionSources.add(outputName);
                    projectionOutputs.add(outputName);
                }
            }

            operator = aggregationConfig.parallelEnabled()
                    ? AggregationOperatorFactory.parallel(
                    operator, query.groupByColumns(), specifications, aggregationConfig)
                    : new AggregateOperator(operator, query.groupByColumns(), specifications);
            return new ProjectionOperator(operator, projectionSources, projectionOutputs);
        }

        List<String> projectionSources = new ArrayList<>();
        List<String> projectionOutputs = new ArrayList<>();
        for (SelectItem item : query.selectItems()) {
            if (item instanceof AllColumns) {
                projectionSources.addAll(schema.columns());
                projectionOutputs.addAll(schema.columns());
            } else if (item instanceof ColumnReference column) {
                projectionSources.add(column.columnName());
                projectionOutputs.add(column.alias().orElse(column.columnName()));
            }
        }
        return new ProjectionOperator(operator, projectionSources, projectionOutputs);
    }

    private TableSchema getSchema(String tableName) throws SqlExecutionException {
        try {
            return Objects.requireNonNull(storage.getSchema(tableName),
                    "Storage returned a null table schema");
        } catch (IOException exception) {
            throw new SqlExecutionException("Unable to read schema for table '" + tableName + "'", exception);
        }
    }

    private void validateQuery(SelectQuery query, TableSchema schema) throws SqlExecutionException {
        Set<String> schemaColumns = Set.copyOf(schema.columns());
        boolean hasAggregate = containsAggregate(query);
        if (!hasAggregate && !query.groupByColumns().isEmpty()) {
            throw new SqlExecutionException("GROUP BY requires at least one aggregate expression");
        }

        Set<String> selectedOutputs = new HashSet<>();
        for (SelectItem item : query.selectItems()) {
            if (item instanceof ColumnReference column) {
                requireColumn(column.columnName(), schemaColumns, "SELECT");
                if (hasAggregate && !query.groupByColumns().contains(column.columnName())) {
                    throw new SqlExecutionException("Selected column '" + column.columnName()
                            + "' must appear in GROUP BY when aggregates are selected");
                }
                requireUniqueOutput(column.alias().orElse(column.columnName()), selectedOutputs);
            } else if (item instanceof AllColumns) {
                if (hasAggregate) {
                    throw new SqlExecutionException("SELECT * cannot be combined with aggregate expressions");
                }
                for (String column : schema.columns()) {
                    requireUniqueOutput(column, selectedOutputs);
                }
            } else if (item instanceof AggregateExpression aggregate) {
                if (!"*".equals(aggregate.columnName())) {
                    requireColumn(aggregate.columnName(), schemaColumns, "aggregate");
                }
                requireUniqueOutput(aggregateOutputName(aggregate), selectedOutputs);
            } else {
                throw new SqlExecutionException("Unsupported SELECT item: " + item);
            }
        }

        Set<String> groupColumns = new HashSet<>();
        for (String column : query.groupByColumns()) {
            requireColumn(column, schemaColumns, "GROUP BY");
            if (!groupColumns.add(column)) {
                throw new SqlExecutionException("Duplicate GROUP BY column: " + column);
            }
        }
        if (hasAggregate) {
            for (SelectItem item : query.selectItems()) {
                if (item instanceof AggregateExpression aggregate
                        && groupColumns.contains(aggregateOutputName(aggregate))) {
                    throw new SqlExecutionException("Aggregate output name '"
                            + aggregateOutputName(aggregate) + "' conflicts with a GROUP BY column");
                }
            }
        }
        if (query.where().isPresent()) {
            validateWhereColumns(query.where().orElseThrow(), schemaColumns);
        }
    }

    private void validateWhereColumns(WhereExpression expression, Set<String> schemaColumns)
            throws SqlExecutionException {
        if (expression instanceof ComparisonExpression comparison) {
            requireColumn(comparison.columnName(), schemaColumns, "WHERE");
        } else if (expression instanceof LogicalExpression logical) {
            validateWhereColumns(logical.left(), schemaColumns);
            validateWhereColumns(logical.right(), schemaColumns);
        }
    }

    private void requireColumn(String column, Set<String> schemaColumns, String clause)
            throws SqlExecutionException {
        if (!schemaColumns.contains(column)) {
            throw new SqlExecutionException(
                    "Column '" + column + "' referenced in " + clause + " does not exist in table schema");
        }
    }

    private Predicate<Row> predicate(WhereExpression expression) {
        if (expression instanceof ComparisonExpression comparison) {
            LiteralValue literal = comparison.value();
            return new ComparisonPredicate(
                    comparison.columnName(), comparison.operator(), literal.value());
        }
        LogicalExpression logical = (LogicalExpression) expression;
        Predicate<Row> left = predicate(logical.left());
        Predicate<Row> right = predicate(logical.right());
        return logical.operator() == LogicalOperator.AND
                ? row -> left.test(row) && right.test(row)
                : row -> left.test(row) || right.test(row);
    }

    private boolean containsAggregate(SelectQuery query) {
        return query.selectItems().stream().anyMatch(AggregateExpression.class::isInstance);
    }

    private List<String> requiredColumns(SelectQuery query, TableSchema schema) {
        LinkedHashSet<String> required = new LinkedHashSet<>();
        for (SelectItem item : query.selectItems()) {
            if (item instanceof AllColumns) {
                return schema.columns();
            }
            if (item instanceof ColumnReference column) {
                required.add(column.columnName());
            } else if (item instanceof AggregateExpression aggregate
                    && !"*".equals(aggregate.columnName())) {
                required.add(aggregate.columnName());
            }
        }
        if (query.where().isPresent()) {
            collectWhereColumns(query.where().orElseThrow(), required);
        }
        required.addAll(query.groupByColumns());
        return List.copyOf(required);
    }

    private void collectWhereColumns(WhereExpression expression, Set<String> columns) {
        if (expression instanceof ComparisonExpression comparison) {
            columns.add(comparison.columnName());
        } else if (expression instanceof LogicalExpression logical) {
            collectWhereColumns(logical.left(), columns);
            collectWhereColumns(logical.right(), columns);
        }
    }

    private List<ScanPredicate> pushedPredicates(WhereExpression expression) {
        if (expression == null || !isFullyPushable(expression)) {
            return List.of();
        }
        List<ScanPredicate> predicates = new ArrayList<>();
        collectPushedPredicates(expression, predicates);
        return List.copyOf(predicates);
    }

    private boolean isFullyPushable(WhereExpression expression) {
        if (expression instanceof ComparisonExpression) {
            return true;
        }
        if (expression instanceof LogicalExpression logical) {
            return logical.operator() == LogicalOperator.AND
                    && isFullyPushable(logical.left())
                    && isFullyPushable(logical.right());
        }
        return false;
    }

    private void collectPushedPredicates(WhereExpression expression, List<ScanPredicate> predicates) {
        if (expression instanceof ComparisonExpression comparison) {
            predicates.add(new ScanPredicate(comparison.columnName(), comparison.operator(),
                    comparison.value().value()));
        } else {
            LogicalExpression logical = (LogicalExpression) expression;
            collectPushedPredicates(logical.left(), predicates);
            collectPushedPredicates(logical.right(), predicates);
        }
    }

    private String formatPushedPredicates(List<ScanPredicate> predicates) {
        return predicates.stream()
                .map(predicate -> predicate.columnName() + " "
                        + comparisonSymbol(predicate.operator()) + " "
                        + formatLiteral(predicate.expectedValue()))
                .reduce((left, right) -> left + " AND " + right)
                .orElseThrow();
    }

    private String aggregateLabel(AggregateExpression aggregate) {
        return aggregate.function() + "(" + aggregate.columnName() + ")";
    }

    private String aggregateOutputName(AggregateExpression aggregate) {
        return aggregate.alias().orElseGet(() -> aggregateLabel(aggregate));
    }

    private String formatWhere(WhereExpression expression) {
        if (expression instanceof ComparisonExpression comparison) {
            return comparison.columnName() + " " + comparisonSymbol(comparison) + " "
                    + formatLiteral(comparison.value());
        }
        LogicalExpression logical = (LogicalExpression) expression;
        return "(" + formatWhere(logical.left()) + " " + logical.operator() + " "
                + formatWhere(logical.right()) + ")";
    }

    private String formatLiteral(LiteralValue literal) {
        if (literal.type() == LiteralValue.Type.STRING) {
            return "'" + literal.value().toString().replace("'", "''") + "'";
        }
        return literal.value().toString();
    }

    private String comparisonSymbol(ComparisonExpression comparison) {
        return comparisonSymbol(comparison.operator());
    }

    private String comparisonSymbol(ComparisonOperator operator) {
        return switch (operator) {
            case EQUALS -> "=";
            case NOT_EQUALS -> "!=";
            case GREATER_THAN -> ">";
            case LESS_THAN -> "<";
            case GREATER_THAN_OR_EQUAL -> ">=";
            case LESS_THAN_OR_EQUAL -> "<=";
        };
    }

    private String formatLiteral(Object value) {
        if (value instanceof String string) {
            return "'" + string.replace("'", "''") + "'";
        }
        return value.toString();
    }

    private void requireUniqueOutput(String outputName, Set<String> selectedOutputs)
            throws SqlExecutionException {
        if (!selectedOutputs.add(outputName)) {
            throw new SqlExecutionException("Duplicate output column name: " + outputName);
        }
    }

}
