package io.github.payal1762005.minisql.engine;

import io.github.payal1762005.minisql.exception.SqlExecutionException;
import io.github.payal1762005.minisql.exception.SqlParseException;
import io.github.payal1762005.minisql.model.QueryPlan;
import io.github.payal1762005.minisql.model.QueryResult;
import io.github.payal1762005.minisql.model.LogicalPlanNode;
import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.model.SelectQuery;
import io.github.payal1762005.minisql.operators.Operator;
import io.github.payal1762005.minisql.operators.AggregationExecutionConfig;
import io.github.payal1762005.minisql.parser.SqlParser;
import io.github.payal1762005.minisql.parser.SqlParserImpl;
import io.github.payal1762005.minisql.planner.LogicalPlanBuilder;
import io.github.payal1762005.minisql.storage.TableStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Executes SQL by coordinating parsing, planning, and operator execution.
 */
public final class DefaultQueryEngine implements QueryEngine {
    private final SqlParser parser;
    private final LogicalPlanBuilder planBuilder;

    public DefaultQueryEngine(TableStorage storage) {
        this(new SqlParserImpl(), new LogicalPlanBuilder(storage));
    }

    public DefaultQueryEngine(
            TableStorage storage, AggregationExecutionConfig aggregationConfig) {
        this(new SqlParserImpl(), new LogicalPlanBuilder(storage, aggregationConfig));
    }

    public DefaultQueryEngine(SqlParser parser, LogicalPlanBuilder planBuilder) {
        this.parser = Objects.requireNonNull(parser, "SQL parser must not be null");
        this.planBuilder = Objects.requireNonNull(planBuilder, "Logical plan builder must not be null");
    }

    @Override
    public QueryResult execute(String sql) throws SqlParseException, SqlExecutionException {
        SelectQuery query = parseSelectQuery(sql);
        if (query.explain()) {
            return explainResult(planBuilder.explain(query));
        }

        Operator operator = planBuilder.build(query);
        try (operator) {
            List<Row> rows = new ArrayList<>();
            while (operator.hasNext()) {
                rows.add(operator.next());
            }
            return new QueryResult(rows);
        } catch (RuntimeException exception) {
            throw new SqlExecutionException("SQL query execution failed", exception);
        }
    }

    /**
     * Inspects a query's logical plan without opening a row scan.
     *
     * @param sql SQL text, optionally prefixed with EXPLAIN
     */
    public LogicalPlanNode explain(String sql) throws SqlParseException, SqlExecutionException {
        return planBuilder.explain(parseSelectQuery(sql));
    }

    private SelectQuery parseSelectQuery(String sql) throws SqlParseException, SqlExecutionException {
        QueryPlan plan = parser.parse(sql);
        if (!(plan instanceof SelectQuery selectQuery)) {
            throw new SqlExecutionException("Unsupported query plan: "
                    + (plan == null ? "null" : plan.getClass().getName()));
        }
        return selectQuery;
    }

    private QueryResult explainResult(LogicalPlanNode plan) {
        List<Row> rows = plan.format().lines()
                .map(line -> new Row(java.util.Map.of("QUERY PLAN", line)))
                .toList();
        return new QueryResult(rows);
    }
}
