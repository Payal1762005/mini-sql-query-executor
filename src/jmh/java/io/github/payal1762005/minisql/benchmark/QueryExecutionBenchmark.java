package io.github.payal1762005.minisql.benchmark;

import io.github.payal1762005.minisql.engine.DefaultQueryEngine;
import io.github.payal1762005.minisql.exception.SqlExecutionException;
import io.github.payal1762005.minisql.exception.SqlParseException;
import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.model.TableSchema;
import io.github.payal1762005.minisql.storage.CsvTableStorage;
import io.github.payal1762005.minisql.storage.RowCursor;
import io.github.payal1762005.minisql.storage.ScanPredicate;
import io.github.payal1762005.minisql.storage.TableStorage;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end query benchmarks for the CSV-backed SQL executor.
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class QueryExecutionBenchmark {
    @Param({"1000", "100000", "1000000"})
    public int rowCount;

    @Param({"0", "16"})
    public int extraColumnCount;

    @Param({"10", "50", "90"})
    public int predicateSelectivityPercent;

    @Param({"uniform", "clustered"})
    public String dataDistribution;

    @Param({"optimized", "projection-only", "predicate-only", "baseline"})
    public String executionVariant;

    private Path dataDirectory;
    private Path csvFile;
    private DefaultQueryEngine engine;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        dataDirectory = Files.createTempDirectory("mini-sql-jmh-");
        csvFile = dataDirectory.resolve("employees.csv");
        writeDataset();

        CsvTableStorage csvStorage = new CsvTableStorage(dataDirectory);
        TableStorage storage = switch (executionVariant) {
            case "optimized" -> csvStorage;
            case "projection-only" -> new ProjectionOnlyStorage(csvStorage);
            case "predicate-only" -> new PredicateOnlyStorage(
                    csvStorage, csvStorage.getSchema("employees").columns());
            case "baseline" -> new BaselineStorage(csvStorage);
            default -> throw new IllegalArgumentException("Unknown execution variant: " + executionVariant);
        };
        engine = new DefaultQueryEngine(storage);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (csvFile != null) {
            Files.deleteIfExists(csvFile);
        }
        if (dataDirectory != null) {
            Files.deleteIfExists(dataDirectory);
        }
    }

    @Benchmark
    public int fullRowScan() throws SqlParseException, SqlExecutionException {
        return engine.execute("SELECT * FROM employees").rows().size();
    }

    @Benchmark
    public int narrowProjection() throws SqlParseException, SqlExecutionException {
        return engine.execute("SELECT name FROM employees").rows().size();
    }

    @Benchmark
    public int selectiveFilter() throws SqlParseException, SqlExecutionException {
        return engine.execute("SELECT name FROM employees WHERE salary > 90000").rows().size();
    }

    @Benchmark
    public int filterAndProjection() throws SqlParseException, SqlExecutionException {
        return engine.execute("SELECT name FROM employees WHERE salary > 90000").rows().size();
    }

    @Benchmark
    public int groupedAverage() throws SqlParseException, SqlExecutionException {
        return engine.execute(
                "SELECT department, AVG(salary) FROM employees GROUP BY department").rows().size();
    }

    private void writeDataset() throws IOException {
        List<String> headers = new ArrayList<>(
                List.of("id", "name", "department", "salary"));
        for (int column = 0; column < extraColumnCount; column++) {
            headers.add("extra_" + column);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", headers));
            writer.newLine();
            for (int row = 0; row < rowCount; row++) {
                boolean matches = switch (dataDistribution) {
                    case "uniform" -> (row * 37L % 100) < predicateSelectivityPercent;
                    case "clustered" ->
                            row < rowCount * predicateSelectivityPercent / 100;
                    default -> throw new IllegalArgumentException(
                            "Unknown data distribution: " + dataDistribution);
                };
                writer.write(Integer.toString(row + 1));
                writer.write(',');
                writer.write("Employee");
                writer.write(Integer.toString(row + 1));
                writer.write(',');
                writer.write("Department");
                writer.write(Integer.toString(row % 10));
                writer.write(',');
                writer.write(matches ? "100000" : "50000");
                for (int column = 0; column < extraColumnCount; column++) {
                    writer.write(',');
                    writer.write("payload");
                    writer.write(Integer.toString((row + column) % 1000));
                }
                writer.newLine();
            }
        }
    }

    private abstract static class DelegatingStorage implements TableStorage {
        protected final CsvTableStorage delegate;

        private DelegatingStorage(CsvTableStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public TableSchema getSchema(String tableName) throws IOException {
            return delegate.getSchema(tableName);
        }

        @Override
        public java.util.stream.Stream<Row> scan(String tableName) throws IOException {
            return delegate.scan(tableName);
        }

        @Override
        public RowCursor openScan(String tableName) throws IOException {
            return delegate.openScan(tableName);
        }
    }

    /**
     * Baseline for Phase 10B/10C: read full rows and evaluate pushed comparisons
     * through the compatibility adapter after Rows have been materialized.
     */
    private static final class BaselineStorage extends DelegatingStorage {
        private BaselineStorage(CsvTableStorage delegate) {
            super(delegate);
        }

        @Override
        public RowCursor openScan(String tableName, List<String> columns) throws IOException {
            return delegate.openScan(tableName);
        }
    }

    /**
     * Keeps column pruning but disables CSV-record predicate evaluation.
     */
    private static final class ProjectionOnlyStorage extends DelegatingStorage {
        private ProjectionOnlyStorage(CsvTableStorage delegate) {
            super(delegate);
        }

        @Override
        public RowCursor openScan(String tableName, List<String> columns) throws IOException {
            return delegate.openScan(tableName, columns);
        }
    }

    /**
     * Keeps pre-Row predicate evaluation but materializes all CSV columns.
     */
    private static final class PredicateOnlyStorage extends DelegatingStorage {
        private final List<String> allColumns;

        private PredicateOnlyStorage(CsvTableStorage delegate, List<String> allColumns) {
            super(delegate);
            this.allColumns = List.copyOf(allColumns);
        }

        @Override
        public RowCursor openScan(
                String tableName, List<String> columns, List<ScanPredicate> predicates) throws IOException {
            return delegate.openScan(tableName, allColumns, predicates);
        }

        @Override
        public RowCursor openScan(String tableName, List<String> columns) throws IOException {
            return delegate.openScan(tableName);
        }
    }
}
