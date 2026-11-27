package io.github.payal1762005.minisql.engine;

import io.github.payal1762005.minisql.exception.SqlExecutionException;
import io.github.payal1762005.minisql.exception.SqlParseException;
import io.github.payal1762005.minisql.model.QueryResult;
import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.model.LogicalPlanNode;
import io.github.payal1762005.minisql.storage.CsvTableStorage;
import io.github.payal1762005.minisql.storage.RowCursor;
import io.github.payal1762005.minisql.storage.TableStorage;
import io.github.payal1762005.minisql.model.TableSchema;
import io.github.payal1762005.minisql.operators.AggregationExecutionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryEngineTest {
    @TempDir
    Path dataDirectory;

    private DefaultQueryEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DefaultQueryEngine(new CsvTableStorage());
    }

    @Test
    void executesSqlAgainstEmployeeCsvAndPreservesSelectedColumns() throws Exception {
        QueryResult result = engine.execute("SELECT first_name FROM employees");

        assertEquals(List.of("first_name"), columnNames(result.rows().get(0)));
        assertEquals(List.of("Employee001", "Employee002", "Employee003", "Employee004",
                "Employee005", "Employee006", "Employee007", "Employee008",
                "Employee009", "Employee010", "Employee011", "Employee012"),
                values(result, "first_name"));
    }

    @Test
    void selectsMultipleColumnsInRequestedOrder() throws Exception {
        QueryResult result = engine.execute("SELECT last_name, department FROM employees");

        assertEquals(List.of("last_name", "department"), columnNames(result.rows().get(0)));
        assertEquals(new Row(Map.of("last_name", "Record001", "department", "OrbitOps")),
                result.rows().get(0));
    }

    @Test
    void executesOrderByAscendingDescendingAndMultipleKeysEndToEnd() throws Exception {
        DefaultQueryEngine localEngine = orderingEngine(List.of(
                orderingRow("1", "Zoe", "Sales", "10"),
                orderingRow("2", "Amy", "Sales", "30"),
                orderingRow("3", "Bo", "Engineering", "30"),
                orderingRow("4", "Cal", "Engineering", "5")));

        assertEquals(List.of("Cal", "Zoe", "Amy", "Bo"),
                values(localEngine.execute("SELECT name FROM employees ORDER BY salary ASC"), "name"));
        assertEquals(List.of("Amy", "Bo", "Zoe", "Cal"),
                values(localEngine.execute("SELECT name FROM employees ORDER BY salary DESC"), "name"));
        assertEquals(List.of("Cal", "Bo", "Zoe", "Amy"),
                values(localEngine.execute(
                        "SELECT name FROM employees ORDER BY department ASC, salary ASC"), "name"));
    }

    @Test
    void executesOrderByWithLimitAndLimitWithoutOrderByEndToEnd() throws Exception {
        DefaultQueryEngine localEngine = orderingEngine(List.of(
                orderingRow("1", "Zoe", "Sales", "10"),
                orderingRow("2", "Amy", "Sales", "30"),
                orderingRow("3", "Bo", "Engineering", "30")));

        assertEquals(List.of("Amy", "Bo"),
                values(localEngine.execute("SELECT name FROM employees ORDER BY salary DESC LIMIT 2"), "name"));
        assertEquals(List.of("Zoe", "Amy"),
                values(localEngine.execute("SELECT name FROM employees LIMIT 2"), "name"));
        assertEquals(List.of(), localEngine.execute("SELECT name FROM employees LIMIT 0").rows());
    }

    @Test
    void executesWhereOrderByLimitAndEmptyResultQueriesEndToEnd() throws Exception {
        DefaultQueryEngine localEngine = orderingEngine(List.of(
                orderingRow("1", "Zoe", "Sales", "10"),
                orderingRow("2", "Amy", "Sales", "30"),
                orderingRow("3", "Bo", "Engineering", "30")));

        assertEquals(List.of("Amy"),
                values(localEngine.execute(
                        "SELECT name FROM employees WHERE department = 'Sales' "
                                + "ORDER BY salary DESC LIMIT 1"), "name"));
        assertEquals(List.of(), localEngine.execute(
                "SELECT name FROM employees WHERE salary > 100 ORDER BY salary LIMIT 2").rows());
    }

    @Test
    void executesGroupedAggregateSortAndLimitWithSerialAndParallelAggregation() throws Exception {
        DefaultQueryEngine serial = orderingEngine(List.of(
                orderingRow("1", "Zoe", "Sales", "10"),
                orderingRow("2", "Amy", "Sales", "30"),
                orderingRow("3", "Bo", "Engineering", "30"),
                orderingRow("4", "Cal", "Engineering", "5")), AggregationExecutionConfig.serial());
        DefaultQueryEngine parallel = orderingEngine(List.of(
                orderingRow("1", "Zoe", "Sales", "10"),
                orderingRow("2", "Amy", "Sales", "30"),
                orderingRow("3", "Bo", "Engineering", "30"),
                orderingRow("4", "Cal", "Engineering", "5")),
                AggregationExecutionConfig.parallel(2, 1));
        String sql = "SELECT department, SUM(salary) AS payroll FROM employees "
                + "GROUP BY department ORDER BY payroll DESC LIMIT 1";

        List<Row> serialResult = serial.execute(sql).rows();
        assertEquals(List.of(row("department", "Sales", "payroll", "40")), serialResult);
        assertEquals(serialResult, parallel.execute(sql).rows());
    }

    @Test
    void sortsNullsLastAndPreservesLegacyQueriesWithoutOrderingOrLimit() throws Exception {
        DefaultQueryEngine localEngine = orderingEngine(List.of(
                orderingRowNullable("1", "Missing", "Sales", null),
                orderingRow("2", "Low", "Sales", "2"),
                orderingRow("3", "High", "Sales", "10")));

        assertEquals(List.of("Low", "High", "Missing"),
                values(localEngine.execute("SELECT name FROM employees ORDER BY salary ASC"), "name"));
        assertEquals(List.of("Employee001", "Employee002", "Employee003", "Employee004",
                        "Employee005", "Employee006", "Employee007", "Employee008",
                        "Employee009", "Employee010", "Employee011", "Employee012"),
                values(engine.execute("SELECT first_name FROM employees"), "first_name"));
    }

    @Test
    void rejectsInvalidOrderByColumnDuringPlanning() {
        SqlExecutionException exception = assertThrows(SqlExecutionException.class,
                () -> engine.execute("SELECT first_name FROM employees ORDER BY missing_column"));

        assertTrue(exception.getMessage().contains("ORDER BY"));
        assertTrue(exception.getMessage().contains("missing_column"));
    }

    @Test
    void selectStarReturnsAllEmployeeColumnsInCsvOrder() throws Exception {
        QueryResult result = engine.execute("SELECT * FROM employees");

        assertEquals(List.of("employee_id", "first_name", "last_name", "department",
                "job_title", "salary", "hire_date"), columnNames(result.rows().get(0)));
        assertEquals("2024-01-01", result.rows().get(0).values().get("hire_date"));
        assertEquals("Signal Gardener", result.rows().get(0).values().get("job_title"));
    }

    @Test
    void selectStarWorksWithWhere() throws Exception {
        QueryResult result = engine.execute(
                "SELECT * FROM employees WHERE employee_id = 'Employee001'");

        assertEquals(1, result.rows().size());
        assertEquals(List.of("employee_id", "first_name", "last_name", "department",
                "job_title", "salary", "hire_date"), columnNames(result.rows().get(0)));
        assertEquals("Employee001", result.rows().get(0).values().get("first_name"));
    }

    @Test
    void returnsColumnAndAggregateAliases() throws Exception {
        QueryResult columns = engine.execute(
                "SELECT first_name AS employee_name, salary AS monthly_salary FROM employees");
        QueryResult aggregate = engine.execute(
                "SELECT COUNT(*) AS employee_count, SUM(salary) AS payroll FROM employees");

        assertEquals(List.of("employee_name", "monthly_salary"),
                columnNames(columns.rows().get(0)));
        assertEquals("Employee001", columns.rows().get(0).values().get("employee_name"));
        assertEquals("101200", columns.rows().get(0).values().get("monthly_salary"));
        assertEquals(List.of("employee_count", "payroll"), columnNames(aggregate.rows().get(0)));
        assertEquals("12", aggregate.rows().get(0).values().get("employee_count"));
        assertEquals("1321100", aggregate.rows().get(0).values().get("payroll"));
    }

    @Test
    void executesNumericAndStringWhereComparisons() throws Exception {
        QueryResult numeric = engine.execute("SELECT first_name FROM employees WHERE salary > 150000");
        QueryResult string = engine.execute(
                "SELECT first_name FROM employees WHERE department = 'PixelForge'");

        assertEquals(List.of("Employee007", "Employee010"), values(numeric, "first_name"));
        assertEquals(List.of("Employee005", "Employee008", "Employee011"),
                values(string, "first_name"));
    }

    @Test
    void executesAndOrAndParenthesizedWhereExpressions() throws Exception {
        QueryResult and = engine.execute(
                "SELECT first_name FROM employees WHERE department = 'OrbitOps' AND salary > 120000");
        QueryResult or = engine.execute(
                "SELECT first_name FROM employees WHERE department = 'PixelForge' OR department = 'NebulaLab'");
        QueryResult grouped = engine.execute(
                "SELECT first_name FROM employees WHERE department = 'OrbitOps' AND (salary < 120000 OR salary > 150000)");

        assertEquals(List.of("Employee002", "Employee010"), values(and, "first_name"));
        assertEquals(List.of("Employee003", "Employee005", "Employee007",
                "Employee008", "Employee011"), values(or, "first_name"));
        assertEquals(List.of("Employee001", "Employee006", "Employee010"),
                values(grouped, "first_name"));
    }

    @Test
    void executesGroupedCountAndSum() throws Exception {
        QueryResult result = engine.execute(
                "SELECT department, COUNT(*), SUM(salary) FROM employees GROUP BY department");

        assertEquals(List.of("department", "COUNT(*)", "SUM(salary)"),
                columnNames(result.rows().get(0)));
        assertEquals("4", valueFor(result, "department", "OrbitOps", "COUNT(*)"));
        assertEquals("517800", valueFor(result, "department", "OrbitOps", "SUM(salary)"));
    }

    @Test
    void executesGlobalCountSumAverageMinimumAndMaximum() throws Exception {
        QueryResult result = engine.execute(
                "SELECT COUNT(*), SUM(salary), AVG(salary), MIN(salary), MAX(salary) FROM employees");

        assertEquals(List.of("COUNT(*)", "SUM(salary)", "AVG(salary)", "MIN(salary)", "MAX(salary)"),
                columnNames(result.rows().get(0)));
        assertEquals("12", result.rows().get(0).values().get("COUNT(*)"));
        assertEquals("1321100", result.rows().get(0).values().get("SUM(salary)"));
        assertEquals("110091.6667", result.rows().get(0).values().get("AVG(salary)"));
        assertEquals("79500", result.rows().get(0).values().get("MIN(salary)"));
        assertEquals("168900", result.rows().get(0).values().get("MAX(salary)"));
    }

    @Test
    void executesCountStarUsingAZeroColumnScan() throws Exception {
        QueryResult result = engine.execute("SELECT COUNT(*) AS employee_count FROM employees");

        assertEquals(List.of(row("employee_count", "12")), result.rows());
    }

    @Test
    void returnsEmptyRowsForNoMatchingRecords() throws Exception {
        QueryResult result = engine.execute("SELECT first_name FROM employees WHERE salary > 999999");

        assertEquals(List.of(), result.rows());
    }

    @Test
    void propagatesInvalidSqlAsParseException() {
        assertThrows(SqlParseException.class, () -> engine.execute("SELECT FROM employees"));
    }

    @Test
    void translatesUnknownTableIntoExecutionExceptionWithCause() {
        SqlExecutionException exception = assertThrows(SqlExecutionException.class,
                () -> engine.execute("SELECT name FROM unknown_table"));

        assertTrue(exception.getMessage().contains("schema"));
        assertInstanceOf(IOException.class, exception.getCause());
    }

    @Test
    void rejectsUnknownColumns() {
        SqlExecutionException exception = assertThrows(SqlExecutionException.class,
                () -> engine.execute("SELECT absent FROM employees"));

        assertTrue(exception.getMessage().contains("absent"));
    }

    @Test
    void rejectsDuplicateOutputAliases() {
        SqlExecutionException exception = assertThrows(SqlExecutionException.class,
                () -> engine.execute(
                        "SELECT first_name AS value, salary AS value FROM employees"));

        assertTrue(exception.getMessage().contains("Duplicate output"));
        assertTrue(exception.getMessage().contains("value"));
    }

    @Test
    void reportsInvalidNumericWhereComparisonAndRetainsNumberFormatCause() throws IOException {
        Files.writeString(dataDirectory.resolve("numeric.csv"), "amount\ninvalid\n");
        DefaultQueryEngine localEngine = new DefaultQueryEngine(new CsvTableStorage(dataDirectory));

        SqlExecutionException exception = assertThrows(SqlExecutionException.class,
                () -> localEngine.execute("SELECT amount FROM numeric WHERE amount > 1"));

        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
        assertInstanceOf(NumberFormatException.class, exception.getCause().getCause());
        assertTrue(exception.getCause().getMessage().contains("invalid"));
    }

    @Test
    void comparesColumnNamesExactlyAndDoesNotResolveAliasesInWhere() throws Exception {
        assertThrows(SqlExecutionException.class,
                () -> engine.execute("SELECT first_name FROM employees WHERE FIRST_NAME = 'Ava'"));
        assertThrows(SqlExecutionException.class,
                () -> engine.execute(
                        "SELECT first_name AS employee_name FROM employees WHERE employee_name = 'Ava'"));
    }

    @Test
    void explainReturnsPlanRowsWithoutExecutingQuery() throws Exception {
        QueryResult result = engine.execute(
                "EXPLAIN SELECT first_name FROM employees WHERE salary > 50000");

        assertEquals(List.of("QUERY PLAN"), columnNames(result.rows().get(0)));
        assertEquals("Projection: first_name", result.rows().get(0).values().get("QUERY PLAN"));
        assertTrue(result.rows().stream().anyMatch(row ->
                row.values().get("QUERY PLAN").contains("Scan: table=employees")));
    }

    @Test
    void explainMethodReturnsStructuredHierarchyWithoutOpeningScan() throws Exception {
        AtomicBoolean scanOpened = new AtomicBoolean();
        TableStorage storage = trackingStorage(scanOpened);
        DefaultQueryEngine localEngine = new DefaultQueryEngine(storage);

        LogicalPlanNode plan = localEngine.explain("EXPLAIN SELECT id FROM tracked WHERE id = 1");

        assertEquals("Projection", plan.operator());
        assertEquals("Scan", plan.children().get(0).operator());
        assertTrue(plan.children().get(0).details().contains("pushed=[id = 1]"));
        assertEquals(false, scanOpened.get());

        localEngine.execute("SELECT id FROM tracked");
        assertTrue(scanOpened.get());
    }

    @Test
    void explainMatchesTheSortAndLimitPlanUsedForExecution() throws Exception {
        DefaultQueryEngine localEngine = orderingEngine(List.of(
                orderingRow("1", "Zoe", "Sales", "10"),
                orderingRow("2", "Amy", "Sales", "30")));
        String sql = "SELECT name FROM employees ORDER BY salary DESC LIMIT 1";

        LogicalPlanNode plan = localEngine.explain(sql);
        QueryResult explainResult = localEngine.execute("EXPLAIN " + sql);

        assertEquals("Limit", plan.operator());
        assertEquals("Projection", plan.children().get(0).operator());
        assertEquals("Sort", plan.children().get(0).children().get(0).operator());
        assertEquals(plan.format(), explainResult.rows().stream()
                .map(row -> row.values().get("QUERY PLAN"))
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow());
        assertEquals(List.of(row("name", "Amy")), localEngine.execute(sql).rows());
    }

    @Test
    void wrapsRuntimeExecutionFailuresAndRetainsCause() throws IOException {
        Path table = dataDirectory.resolve("numeric.csv");
        Files.writeString(table, "amount\nnot-a-number\n");
        DefaultQueryEngine localEngine = new DefaultQueryEngine(new CsvTableStorage(dataDirectory));

        SqlExecutionException exception = assertThrows(SqlExecutionException.class,
                () -> localEngine.execute("SELECT SUM(amount) FROM numeric"));

        assertTrue(exception.getMessage().contains("execution failed"));
        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
        assertTrue(exception.getCause().getMessage().contains("not-a-number"));
    }

    @Test
    void closesStorageCursorAfterSuccessfulExecution() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        DefaultQueryEngine localEngine = engineWithTrackingStorage(closed, false);

        assertEquals(List.of(row("id", "1")), localEngine.execute("SELECT id FROM tracked").rows());
        assertTrue(closed.get());
    }

    @Test
    void closesStorageCursorAfterExecutionFailure() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        DefaultQueryEngine localEngine = engineWithTrackingStorage(closed, true);

        assertThrows(SqlExecutionException.class,
                () -> localEngine.execute("SELECT SUM(id) FROM tracked"));
        assertTrue(closed.get());
    }

    private DefaultQueryEngine engineWithTrackingStorage(AtomicBoolean closed, boolean invalidNumber)
            throws IOException {
        List<Row> rows = List.of(row("id", invalidNumber ? "not-a-number" : "1"));
        return new DefaultQueryEngine(trackingStorage(closed, rows, closed));
    }

    private DefaultQueryEngine orderingEngine(List<Row> rows) {
        return orderingEngine(rows, AggregationExecutionConfig.serial());
    }

    private DefaultQueryEngine orderingEngine(
            List<Row> rows, AggregationExecutionConfig aggregationConfig) {
        TableStorage storage = new TableStorage() {
            @Override
            public TableSchema getSchema(String tableName) {
                return new TableSchema(tableName, List.of("id", "name", "department", "salary"));
            }

            @Override
            public java.util.stream.Stream<Row> scan(String tableName) {
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
                    }
                };
            }
        };
        return new DefaultQueryEngine(storage, aggregationConfig);
    }

    private Row orderingRow(String id, String name, String department, String salary) {
        return orderingRowNullable(id, name, department, salary);
    }

    private Row orderingRowNullable(String id, String name, String department, String salary) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("name", name);
        values.put("department", department);
        values.put("salary", salary);
        return new Row(values);
    }

    private TableStorage trackingStorage(AtomicBoolean scanOpened) {
        return trackingStorage(scanOpened, List.of(row("id", "1")), scanOpened);
    }

    private TableStorage trackingStorage(
            AtomicBoolean scanOpened, List<Row> rows, AtomicBoolean closed) {
        return new TableStorage() {
            @Override
            public TableSchema getSchema(String tableName) {
                return new TableSchema(tableName, List.of("id"));
            }

            @Override
            public java.util.stream.Stream<Row> scan(String tableName) {
                return rows.stream();
            }

            @Override
            public RowCursor openScan(String tableName) {
                scanOpened.set(true);
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
                        closed.set(true);
                    }
                };
            }
        };
    }

    private List<String> columnNames(Row row) {
        return new ArrayList<>(row.values().keySet());
    }

    private List<String> values(QueryResult result, String column) {
        return result.rows().stream().map(row -> row.values().get(column)).toList();
    }

    private String valueFor(QueryResult result, String groupColumn, String groupValue, String resultColumn) {
        return result.rows().stream()
                .filter(row -> groupValue.equals(row.values().get(groupColumn)))
                .map(row -> row.values().get(resultColumn))
                .findFirst()
                .orElseThrow();
    }

    private Row row(String key, String value) {
        return new Row(Map.of(key, value));
    }

    private Row row(String key1, String value1, String key2, String value2) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        return new Row(values);
    }
}
