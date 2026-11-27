package io.github.payal1762005.minisql.planner;

import io.github.payal1762005.minisql.exception.SqlExecutionException;
import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.model.LogicalPlanNode;
import io.github.payal1762005.minisql.operators.Operator;
import io.github.payal1762005.minisql.parser.SqlParserImpl;
import io.github.payal1762005.minisql.storage.CsvTableStorage;
import io.github.payal1762005.minisql.storage.RowCursor;
import io.github.payal1762005.minisql.storage.TableStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicalPlanBuilderTest {
    @TempDir
    Path dataDirectory;

    private LogicalPlanBuilder builder;
    private SqlParserImpl parser;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(dataDirectory.resolve("staff.csv"), """
                id,name,department,age,salary
                1,Ava,Engineering,30,100.50
                2,Noah,Sales,42,80
                3,Mia,Engineering,35,120
                4,Leo,Sales,25,60
                5,Ivy,Engineering,28,90
                """);
        builder = new LogicalPlanBuilder(new CsvTableStorage(dataDirectory));
        parser = new SqlParserImpl();
    }

    @Test
    void executesSelectWithOneColumn() throws Exception {
        assertEquals(List.of(row("name", "Ava"), row("name", "Noah"), row("name", "Mia"),
                        row("name", "Leo"), row("name", "Ivy")),
                execute("SELECT name FROM staff"));
    }

    @Test
    void executesSelectWithMultipleColumns() throws Exception {
        assertEquals(List.of(row("name", "Ava", "department", "Engineering"),
                        row("name", "Noah", "department", "Sales"),
                        row("name", "Mia", "department", "Engineering"),
                        row("name", "Leo", "department", "Sales"),
                        row("name", "Ivy", "department", "Engineering")),
                execute("SELECT name, department FROM staff"));
    }

    @Test
    void expandsSelectStarInSchemaOrder() throws Exception {
        List<Row> result = execute("SELECT * FROM staff");

        assertEquals(List.of("id", "name", "department", "age", "salary"),
                new ArrayList<>(result.get(0).values().keySet()));
        assertEquals(row("id", "1", "name", "Ava", "department", "Engineering"),
                new Row(result.get(0).values().entrySet().stream()
                        .filter(entry -> List.of("id", "name", "department").contains(entry.getKey()))
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left,
                                LinkedHashMap::new))));
    }

    @Test
    void filtersSelectStarWithoutChangingSchemaOrder() throws Exception {
        List<Row> result = execute("SELECT * FROM staff WHERE id = 2");

        assertEquals(1, result.size());
        assertEquals(List.of("id", "name", "department", "age", "salary"),
                new ArrayList<>(result.get(0).values().keySet()));
        assertEquals("Noah", result.get(0).values().get("name"));
    }

    @Test
    void projectsColumnAndAggregateAliases() throws Exception {
        assertEquals(List.of(row("employee_name", "Ava", "monthly_salary", "100.50")),
                execute("SELECT name AS employee_name, salary AS monthly_salary FROM staff WHERE id = 1"));
        assertEquals(List.of(row("department", "Engineering", "total_salary", "310.5")),
                execute("SELECT department, SUM(salary) AS total_salary FROM staff GROUP BY department")
                        .stream().filter(row -> "Engineering".equals(row.values().get("department"))).toList());
    }

    @Test
    void filtersUsingNumericComparison() throws Exception {
        assertEquals(List.of(row("name", "Noah"), row("name", "Mia")),
                execute("SELECT name FROM staff WHERE age >= 35"));
    }

    @Test
    void filtersUsingStringComparison() throws Exception {
        assertEquals(List.of(row("name", "Noah"), row("name", "Leo")),
                execute("SELECT name FROM staff WHERE department = 'Sales'"));
    }

    @Test
    void combinesWhereConditionsWithAnd() throws Exception {
        assertEquals(List.of(row("name", "Ava"), row("name", "Ivy")),
                execute("SELECT name FROM staff WHERE department = 'Engineering' AND age < 32"));
    }

    @Test
    void combinesWhereConditionsWithOrWithoutFlattening() throws Exception {
        assertEquals(List.of(row("name", "Ava"), row("name", "Noah"), row("name", "Ivy")),
                execute("SELECT name FROM staff WHERE department = 'Engineering' AND age < 32 OR age = 42"));
    }

    @Test
    void preservesParenthesizedWhereLogic() throws Exception {
        assertEquals(List.of(row("name", "Ava"), row("name", "Mia"),
                        row("name", "Leo"), row("name", "Ivy")),
                execute("SELECT name FROM staff WHERE department = 'Engineering' AND (age < 32 OR age > 32) OR age = 25"));
    }

    @Test
    void groupsByMultipleColumnsWithCountAndSum() throws Exception {
        assertEquals(List.of(
                        row("department", "Engineering", "COUNT(*)", "3", "SUM(salary)", "310.5"),
                        row("department", "Sales", "COUNT(*)", "2", "SUM(salary)", "140")),
                execute("SELECT department, COUNT(*), SUM(salary) FROM staff GROUP BY department"));
    }

    @Test
    void groupsByMultipleColumns() throws Exception {
        assertEquals(List.of(
                        row("department", "Engineering", "age", "30", "COUNT(*)", "1"),
                        row("department", "Sales", "age", "42", "COUNT(*)", "1"),
                        row("department", "Engineering", "age", "35", "COUNT(*)", "1"),
                        row("department", "Sales", "age", "25", "COUNT(*)", "1"),
                        row("department", "Engineering", "age", "28", "COUNT(*)", "1")),
                execute("SELECT department, age, COUNT(*) FROM staff GROUP BY department, age"));
    }

    @Test
    void groupsAndComputesAverage() throws Exception {
        assertEquals(List.of(
                        row("department", "Engineering", "AVG(age)", "31.0000"),
                        row("department", "Sales", "AVG(age)", "33.5000")),
                execute("SELECT department, AVG(age) FROM staff GROUP BY department"));
    }

    @Test
    void performsGlobalAggregation() throws Exception {
        assertEquals(List.of(row("COUNT(*)", "5", "SUM(salary)", "450.5", "AVG(age)", "32.0000")),
                execute("SELECT COUNT(*), SUM(salary), AVG(age) FROM staff"));
    }

    @Test
    void rejectsUnknownColumnsBeforeOpeningExecutionScan() throws Exception {
        SqlExecutionException exception = assertThrows(SqlExecutionException.class,
                () -> builder.build(parser.parse("SELECT absent FROM staff")));

        assertTrue(exception.getMessage().contains("absent"));
        assertTrue(exception.getMessage().contains("SELECT"));
    }

    @Test
    void rejectsUnknownColumnsInWhereGroupByAndAggregate() throws Exception {
        for (String sql : List.of(
                "SELECT name FROM staff WHERE absent = 1",
                "SELECT COUNT(*) FROM staff GROUP BY absent",
                "SELECT SUM(absent) FROM staff")) {
            SqlExecutionException exception = assertThrows(SqlExecutionException.class,
                    () -> builder.build(parser.parse(sql)));
            assertTrue(exception.getMessage().contains("absent"));
        }
    }

    @Test
    void rejectsUngroupedSelectedColumnsAndGroupByWithoutAggregate() throws Exception {
        assertThrows(SqlExecutionException.class, () -> builder.build(
                parser.parse("SELECT name, COUNT(*) FROM staff GROUP BY department")));
        assertThrows(SqlExecutionException.class, () -> builder.build(
                parser.parse("SELECT department FROM staff GROUP BY department")));
    }

    @Test
    void rejectsDuplicateSelectedAndGroupByColumns() throws Exception {
        assertThrows(SqlExecutionException.class, () -> builder.build(
                parser.parse("SELECT name, name FROM staff")));
        assertThrows(SqlExecutionException.class, () -> builder.build(
                parser.parse("SELECT COUNT(*) FROM staff GROUP BY department, department")));
    }

    @Test
    void rejectsDuplicateOutputAliases() throws Exception {
        SqlExecutionException exception = assertThrows(SqlExecutionException.class,
                () -> builder.build(parser.parse("SELECT name AS value, salary AS value FROM staff")));

        assertTrue(exception.getMessage().contains("Duplicate output"));
        assertTrue(exception.getMessage().contains("value"));
    }

    @Test
    void resolvesSourceColumnNamesAndAliasesCaseSensitively() throws Exception {
        assertThrows(SqlExecutionException.class,
                () -> builder.build(parser.parse("SELECT Name FROM staff")));

        List<Row> result = execute("SELECT name AS Value, salary AS value FROM staff WHERE id = 1");
        assertEquals(List.of("Value", "value"), new ArrayList<>(result.get(0).values().keySet()));
        assertEquals("Ava", result.get(0).values().get("Value"));
        assertEquals("100.50", result.get(0).values().get("value"));
    }

    @Test
    void resolvesTableNamesCaseSensitivelyEvenOnCaseInsensitiveStorage() throws Exception {
        AtomicInteger scans = new AtomicInteger();
        TableStorage storage = new TableStorage() {
            @Override
            public io.github.payal1762005.minisql.model.TableSchema getSchema(String tableName) {
                return new io.github.payal1762005.minisql.model.TableSchema("Staff", List.of("id"));
            }

            @Override
            public Stream<Row> scan(String tableName) {
                return Stream.empty();
            }

            @Override
            public RowCursor openScan(String tableName) {
                scans.incrementAndGet();
                throw new AssertionError("Semantic validation must reject before opening a scan");
            }
        };
        LogicalPlanBuilder caseBuilder = new LogicalPlanBuilder(storage);

        SqlExecutionException exception = assertThrows(SqlExecutionException.class,
                () -> caseBuilder.build(parser.parse("SELECT id FROM staff")));

        assertTrue(exception.getMessage().contains("case-sensitive"));
        assertEquals(0, scans.get());
    }

    @Test
    void validatesSemanticErrorsBeforeOpeningScan() throws Exception {
        AtomicInteger scans = new AtomicInteger();
        LogicalPlanBuilder validatingBuilder = new LogicalPlanBuilder(
                storageWithRows(List.of(row("id", "1")), scans, new AtomicInteger()));

        for (String sql : List.of(
                "SELECT absent FROM staff",
                "SELECT SUM(absent) FROM staff",
                "SELECT COUNT(*) FROM staff GROUP BY absent",
                "SELECT id FROM staff WHERE absent = 1",
                "SELECT id, COUNT(*) FROM staff")) {
            assertThrows(SqlExecutionException.class,
                    () -> validatingBuilder.build(parser.parse(sql)));
        }
        assertEquals(0, scans.get());
    }

    @Test
    void preservesGlobalAggregationOnAnEmptyTable() throws Exception {
        Files.writeString(dataDirectory.resolve("empty.csv"), "id,amount\n");
        List<Row> results = execute("SELECT COUNT(*), SUM(amount) FROM empty");

        assertEquals(List.of(rowNullable("COUNT(*)", "0", "SUM(amount)", null)), results);
    }

    @Test
    void handlesNullValuesInGroupedAggregates() throws Exception {
        List<Row> rows = List.of(
                rowNullable("department", null, "amount", "5"),
                rowNullable("department", null, "amount", null),
                rowNullable("department", "Sales", "amount", "8"));
        LogicalPlanBuilder nullBuilder = new LogicalPlanBuilder(
                storageWithRows(rows, new AtomicInteger(), new AtomicInteger()));

        List<Row> result = consume(nullBuilder.build(
                parser.parse("SELECT department, COUNT(amount), SUM(amount) FROM staff GROUP BY department")));

        assertEquals(rowNullable("department", null, "COUNT(amount)", "1", "SUM(amount)", "5"),
                result.get(0));
        assertEquals(row("department", "Sales", "COUNT(amount)", "1", "SUM(amount)", "8"),
                result.get(1));
    }

    @Test
    void repeatedPipelineCloseClosesCursorOnlyOnce() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        Operator operator = new LogicalPlanBuilder(
                storageWithRows(List.of(row("id", "1")), new AtomicInteger(), closes))
                .build(parser.parse("SELECT id FROM staff"));

        operator.close();
        operator.close();

        assertEquals(1, closes.get());
    }

    @Test
    void rejectsAggregateWildcardAndAggregateOutputCollisionsClearly() throws Exception {
        assertThrows(SqlExecutionException.class,
                () -> builder.build(parser.parse("SELECT * , COUNT(*) FROM staff")));
        SqlExecutionException exception = assertThrows(SqlExecutionException.class,
                () -> builder.build(parser.parse(
                        "SELECT SUM(salary) AS department FROM staff GROUP BY department")));
        assertTrue(exception.getMessage().contains("conflicts"));
    }

    @Test
    void pushesSelectedAndWhereColumnsIntoScan() throws Exception {
        AtomicReference<List<String>> scannedColumns = new AtomicReference<>();
        LogicalPlanBuilder pushdownBuilder = new LogicalPlanBuilder(
                storageCapturingColumns(scannedColumns));

        assertEquals(List.of(row("name", "Ava")),
                consume(pushdownBuilder.build(parser.parse(
                        "SELECT name FROM staff WHERE salary > 50 AND id = 1"))));
        assertEquals(List.of("name", "salary", "id"), scannedColumns.get());
    }

    @Test
    void pushesSafeAndComparisonsAndRetainsOrAsResidualFilter() throws Exception {
        LogicalPlanNode pushed = builder.explain(parser.parse(
                "SELECT name FROM staff WHERE salary > 50 AND id = 1"));
        assertEquals("Projection: name\n"
                + "  Scan: table=staff, columns=[name, salary, id], pushed=[salary > 50 AND id = 1]",
                pushed.format());

        LogicalPlanNode residual = builder.explain(parser.parse(
                "SELECT name FROM staff WHERE salary > 50 OR id = 1"));
        assertEquals("Projection: name\n"
                + "  Filter: residual: (salary > 50 OR id = 1)\n"
                + "    Scan: table=staff, columns=[name, salary, id]",
                residual.format());
    }

    @Test
    void parenthesizedOrRemainsResidualAndPreservesResults() throws Exception {
        String sql = "SELECT name FROM staff WHERE id = 1 AND (salary > 110 OR age < 31)";
        LogicalPlanNode plan = builder.explain(parser.parse(sql));

        assertTrue(plan.format().contains("Filter: residual: "));
        assertEquals(List.of(row("name", "Ava")), execute(sql));
    }

    @Test
    void pushedPredicatePreservesNullFailureAndClosesCompatibleStorageCursor() throws Exception {
        AtomicInteger scans = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        LogicalPlanBuilder nullValueBuilder = new LogicalPlanBuilder(storageWithRows(
                List.of(rowNullable("id", null, "name", "Ava")), scans, closes));

        Operator operator = nullValueBuilder.build(parser.parse(
                "SELECT id FROM staff WHERE id = 1"));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> consume(operator));

        assertTrue(exception.getMessage().contains("has a null value"));
        assertEquals(1, scans.get());
        assertEquals(1, closes.get());
    }

    @Test
    void pushesGroupingAndAggregateArgumentColumnsIntoScan() throws Exception {
        AtomicReference<List<String>> scannedColumns = new AtomicReference<>();
        LogicalPlanBuilder pushdownBuilder = new LogicalPlanBuilder(
                storageCapturingColumns(scannedColumns));

        List<Row> results = consume(pushdownBuilder.build(parser.parse(
                "SELECT department, SUM(salary) FROM staff GROUP BY department")));

        assertEquals(List.of("department", "salary"), scannedColumns.get());
        assertEquals("100.5", results.get(0).values().get("SUM(salary)"));
    }

    @Test
    void countStarUsesZeroColumnScanAndSelectStarUsesAllSchemaColumns() throws Exception {
        AtomicReference<List<String>> scannedColumns = new AtomicReference<>();
        LogicalPlanBuilder pushdownBuilder = new LogicalPlanBuilder(
                storageCapturingColumns(scannedColumns));

        assertEquals("1", consume(pushdownBuilder.build(
                parser.parse("SELECT COUNT(*) FROM staff"))).get(0).values().get("COUNT(*)"));
        assertEquals(List.of(), scannedColumns.get());

        consume(pushdownBuilder.build(parser.parse("SELECT * FROM staff")));
        assertEquals(List.of("id", "name", "department", "age", "salary", "amount"),
                scannedColumns.get());
    }

    @Test
    void explainsSimpleSelectHierarchy() throws Exception {
        LogicalPlanNode plan = builder.explain(parser.parse("SELECT name FROM staff"));

        assertEquals("Projection: name\n  Scan: table=staff, columns=[name]", plan.format());
    }

    @Test
    void explainsWhereAndGroupedAggregationHierarchy() throws Exception {
        LogicalPlanNode filtered = builder.explain(
                parser.parse("SELECT name FROM staff WHERE salary > 50000"));
        assertEquals("Projection: name\n"
                + "  Scan: table=staff, columns=[name, salary], pushed=[salary > 50000]",
                filtered.format());

        LogicalPlanNode grouped = builder.explain(
                parser.parse("SELECT department, COUNT(*), SUM(salary) FROM staff GROUP BY department"));
        assertTrue(grouped.format().contains("Aggregate: functions=[COUNT(*), SUM(salary)], "
                + "GROUP BY [department]"));
        assertTrue(grouped.format().contains("Scan: table=staff, columns=[department, salary]"));
        assertTrue(grouped.format().indexOf("Aggregate")
                < grouped.format().indexOf("Scan"));
    }

    @Test
    void explainsGlobalAggregation() throws Exception {
        LogicalPlanNode plan = builder.explain(
                parser.parse("SELECT COUNT(*), AVG(salary) FROM staff"));

        assertTrue(plan.format().contains("GROUP BY [global]"));
        assertTrue(plan.format().contains("functions=[COUNT(*), AVG(salary)]"));
    }

    @Test
    void explainValidatesTableAndColumnsWithoutOpeningScan() throws Exception {
        AtomicInteger scans = new AtomicInteger();
        LogicalPlanBuilder noScanBuilder = new LogicalPlanBuilder(
                storageWithRows(List.of(row("id", "1")), scans, new AtomicInteger()));

        noScanBuilder.explain(parser.parse("SELECT id FROM staff WHERE id > 0"));
        assertEquals(0, scans.get());

        assertThrows(SqlExecutionException.class,
                () -> noScanBuilder.explain(parser.parse("SELECT absent FROM staff")));
        assertEquals(0, scans.get());

        assertThrows(SqlExecutionException.class,
                () -> builder.explain(parser.parse("SELECT id FROM missing")));
        assertEquals(0, scans.get());
    }

    @Test
    void explainShowsPushedDownColumnsAndGlobalEmptyColumnList() throws Exception {
        assertEquals("Projection: name\n"
                        + "  Scan: table=staff, columns=[name, salary], pushed=[salary > 50000]",
                builder.explain(parser.parse(
                        "SELECT name FROM staff WHERE salary > 50000")).format());
        assertTrue(builder.explain(parser.parse("SELECT COUNT(*) FROM staff"))
                .format().contains("Scan: table=staff, columns=[]"));
    }

    private List<Row> execute(String sql) throws Exception {
        return consume(builder.build(parser.parse(sql)));
    }

    private List<Row> consume(Operator operator) {
        List<Row> rows = new ArrayList<>();
        try (operator) {
            while (operator.hasNext()) {
                rows.add(operator.next());
            }
        }
        return rows;
    }

    private TableStorage storageWithRows(List<Row> rows, AtomicInteger scans, AtomicInteger closes) {
        List<String> columns = List.of("id", "name", "department", "age", "salary", "amount");
        return new TableStorage() {
            @Override
            public io.github.payal1762005.minisql.model.TableSchema getSchema(String tableName) {
                return new io.github.payal1762005.minisql.model.TableSchema(tableName, columns);
            }

            @Override
            public Stream<Row> scan(String tableName) {
                return rows.stream();
            }

            @Override
            public RowCursor openScan(String tableName) {
                scans.incrementAndGet();
                var iterator = rows.iterator();
                return new RowCursor() {
                    private boolean closed;

                    @Override
                    public boolean hasNext() {
                        return !closed && iterator.hasNext();
                    }

                    @Override
                    public Row next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        return iterator.next();
                    }

                    @Override
                    public void close() {
                        if (!closed) {
                            closed = true;
                            closes.incrementAndGet();
                        }
                    }
                };
            }
        };
    }

    private TableStorage storageCapturingColumns(AtomicReference<List<String>> scannedColumns) {
        Map<String, String> rowValues = new LinkedHashMap<>();
        rowValues.put("id", "1");
        rowValues.put("name", "Ava");
        rowValues.put("department", "Engineering");
        rowValues.put("age", "30");
        rowValues.put("salary", "100.50");
        rowValues.put("amount", "10");
        List<Row> rows = List.of(new Row(rowValues));
        List<String> columns = List.of("id", "name", "department", "age", "salary", "amount");
        return new TableStorage() {
            @Override
            public io.github.payal1762005.minisql.model.TableSchema getSchema(String tableName) {
                return new io.github.payal1762005.minisql.model.TableSchema(tableName, columns);
            }

            @Override
            public Stream<Row> scan(String tableName) {
                return rows.stream();
            }

            @Override
            public RowCursor openScan(String tableName) {
                return openScan(tableName, columns);
            }

            @Override
            public RowCursor openScan(String tableName, List<String> requestedColumns) {
                scannedColumns.set(List.copyOf(requestedColumns));
                var iterator = rows.iterator();
                return new RowCursor() {
                    private boolean closed;

                    @Override
                    public boolean hasNext() {
                        return !closed && iterator.hasNext();
                    }

                    @Override
                    public Row next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        Row source = iterator.next();
                        Map<String, String> values = new LinkedHashMap<>();
                        for (String column : requestedColumns) {
                            values.put(column, source.values().get(column));
                        }
                        return new Row(values);
                    }

                    @Override
                    public void close() {
                        closed = true;
                    }
                };
            }
        };
    }

    private Row rowNullable(String key1, String value1, String key2, String value2) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        return new Row(values);
    }

    private Row rowNullable(String key1, String value1, String key2, String value2,
                            String key3, String value3) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        values.put(key3, value3);
        return new Row(values);
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

    private Row row(String key1, String value1, String key2, String value2,
                    String key3, String value3) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        values.put(key3, value3);
        return new Row(values);
    }
}
