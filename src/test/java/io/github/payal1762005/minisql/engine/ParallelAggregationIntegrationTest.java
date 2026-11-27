package io.github.payal1762005.minisql.engine;

import io.github.payal1762005.minisql.exception.SqlExecutionException;
import io.github.payal1762005.minisql.model.LogicalPlanNode;
import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.model.TableSchema;
import io.github.payal1762005.minisql.operators.AggregationExecutionConfig;
import io.github.payal1762005.minisql.storage.RowCursor;
import io.github.payal1762005.minisql.storage.TableStorage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelAggregationIntegrationTest {
    @Test
    void serialAndParallelQueriesReturnSameGroupedResults() throws Exception {
        List<Row> rows = List.of(
                row("department", "OrbitOps", "amount", "0.10"),
                row("department", "NebulaLab", "amount", "2"),
                row("department", "OrbitOps", "amount", "0.20"),
                row("department", "NebulaLab", "amount", "4"));
        String sql = "SELECT department, COUNT(*), COUNT(amount), SUM(amount), "
                + "AVG(amount), MIN(amount), MAX(amount) FROM staff GROUP BY department";
        DefaultQueryEngine serial = engine(rows, AggregationExecutionConfig.serial(), new AtomicInteger());
        DefaultQueryEngine parallel = engine(rows, AggregationExecutionConfig.parallel(3, 1),
                new AtomicInteger());

        assertEquals(serial.execute(sql).rows(), parallel.execute(sql).rows());
        assertEquals(List.of(
                        row("department", "OrbitOps", "COUNT(*)", "2", "COUNT(amount)", "2",
                                "SUM(amount)", "0.3", "AVG(amount)", "0.1500",
                                "MIN(amount)", "0.1", "MAX(amount)", "0.2"),
                        row("department", "NebulaLab", "COUNT(*)", "2", "COUNT(amount)", "2",
                                "SUM(amount)", "6", "AVG(amount)", "3.0000",
                                "MIN(amount)", "2", "MAX(amount)", "4")),
                parallel.execute(sql).rows());
    }

    @Test
    void parallelGlobalAndGroupedAggregationHandleEmptyInput() throws Exception {
        DefaultQueryEngine parallel = engine(List.of(),
                AggregationExecutionConfig.parallel(2, 4), new AtomicInteger());

        assertEquals(List.of(row("row_count", "0", "total", null)),
                parallel.execute(
                        "SELECT COUNT(*) AS row_count, SUM(amount) AS total FROM staff").rows());
        assertEquals(List.of(), parallel.execute(
                "SELECT department, COUNT(*) FROM staff GROUP BY department").rows());
    }

    @Test
    void parallelAggregationPreservesNullGroupKeysAndNullAggregateValues() throws Exception {
        List<Row> rows = List.of(
                rowWithNull("department", null, "amount", null),
                row("department", "OrbitOps", "amount", "1.25"),
                rowWithNull("department", null, "amount", "2.75"));
        DefaultQueryEngine parallel = engine(rows,
                AggregationExecutionConfig.parallel(2, 1), new AtomicInteger());

        assertEquals(List.of(
                        rowWithNull("department", null, "COUNT(*)", "2", "COUNT(amount)", "1",
                                "SUM(amount)", "2.75", "AVG(amount)", "2.7500"),
                        row("department", "OrbitOps", "COUNT(*)", "1", "COUNT(amount)", "1",
                                "SUM(amount)", "1.25", "AVG(amount)", "1.2500")),
                parallel.execute("SELECT department, COUNT(*), COUNT(amount), SUM(amount), "
                        + "AVG(amount) FROM staff GROUP BY department").rows());
    }

    @Test
    void parallelWorkerFailureIsPropagatedAndCursorIsClosed() throws Exception {
        AtomicInteger closeCount = new AtomicInteger();
        DefaultQueryEngine parallel = engine(
                List.of(row("department", "OrbitOps", "amount", "not-numeric")),
                AggregationExecutionConfig.parallel(2, 1), closeCount);

        SqlExecutionException exception = assertThrows(SqlExecutionException.class,
                () -> parallel.execute("SELECT SUM(amount) FROM staff"));

        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
        assertTrue(exception.getCause().getMessage().contains("not-numeric"));
        assertEquals(1, closeCount.get());
    }

    @Test
    void explainReportsSelectedAggregationStrategyAndSettings() throws Exception {
        List<Row> rows = List.of(row("department", "OrbitOps", "amount", "1"));
        DefaultQueryEngine serial = engine(rows, AggregationExecutionConfig.serial(),
                new AtomicInteger());
        DefaultQueryEngine parallel = engine(rows, AggregationExecutionConfig.parallel(3, 17),
                new AtomicInteger());
        String sql = "SELECT department, SUM(amount) FROM staff GROUP BY department";

        LogicalPlanNode serialPlan = serial.explain(sql);
        LogicalPlanNode parallelPlan = parallel.explain(sql);
        String parallelExplain = parallel.execute("EXPLAIN " + sql).rows().stream()
                .map(row -> row.values().get("QUERY PLAN"))
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();

        assertEquals("Aggregate", serialPlan.children().get(0).operator());
        assertEquals("ParallelAggregate", parallelPlan.children().get(0).operator());
        assertTrue(parallelPlan.format().contains("workers=3, batchSize=17, maxInFlightBatches=3"));
        assertTrue(parallelExplain.contains("ParallelAggregate"));
    }

    @Test
    void parallelConfigurationDoesNotChangeNonAggregateQueries() throws Exception {
        DefaultQueryEngine parallel = engine(
                List.of(row("department", "OrbitOps", "amount", "1")),
                AggregationExecutionConfig.parallel(2, 1), new AtomicInteger());

        assertEquals(List.of(row("department", "OrbitOps")),
                parallel.execute("SELECT department FROM staff").rows());
        assertEquals("Projection", parallel.explain("SELECT department FROM staff").operator());
    }

    @Test
    void invalidAggregationConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AggregationExecutionConfig.parallel(0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> AggregationExecutionConfig.parallel(2, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AggregationExecutionConfig(false, -1, 10));
        assertThrows(NullPointerException.class,
                () -> new DefaultQueryEngine(storage(List.of(), new AtomicInteger()), null));
    }

    private DefaultQueryEngine engine(
            List<Row> rows, AggregationExecutionConfig config, AtomicInteger closeCount) {
        return new DefaultQueryEngine(storage(rows, closeCount), config);
    }

    private TableStorage storage(List<Row> rows, AtomicInteger closeCount) {
        return new TableStorage() {
            @Override
            public TableSchema getSchema(String tableName) {
                return new TableSchema(tableName, List.of("department", "amount"));
            }

            @Override
            public Stream<Row> scan(String tableName) {
                return rows.stream();
            }

            @Override
            public RowCursor openScan(String tableName) {
                var iterator = rows.iterator();
                return new RowCursor() {
                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public Row next() {
                        return iterator.next();
                    }

                    @Override
                    public void close() {
                        closeCount.incrementAndGet();
                    }
                };
            }
        };
    }

    private Row row(String... entries) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put(entries[index], entries[index + 1]);
        }
        return new Row(values);
    }

    private Row rowWithNull(String... entries) {
        return row(entries);
    }
}
