package io.github.payal1762005.minisql.benchmark;

import io.github.payal1762005.minisql.engine.DefaultQueryEngine;
import io.github.payal1762005.minisql.exception.SqlExecutionException;
import io.github.payal1762005.minisql.exception.SqlParseException;
import io.github.payal1762005.minisql.model.QueryResult;
import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.operators.AggregationExecutionConfig;
import io.github.payal1762005.minisql.storage.CsvTableStorage;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Compares serial and parallel grouped aggregation over deterministic CSV data.
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ParallelAggregationBenchmark {
    private static final String QUERY =
            "SELECT department, AVG(salary) FROM employees GROUP BY department";
    private static final int BATCH_SIZE = 4096;
    private static final int DEPARTMENT_COUNT = 10;

    @Param({"100000", "1000000"})
    public int rowCount;

    @Param({"serial", "parallel-1", "parallel-2", "parallel-4", "parallel-8"})
    public String aggregationMode;

    private Path dataDirectory;
    private Path csvFile;
    private DefaultQueryEngine engine;

    @Setup(Level.Trial)
    public void setUp() throws IOException, SqlParseException, SqlExecutionException {
        try {
            dataDirectory = Files.createTempDirectory("mini-sql-parallel-aggregation-");
            csvFile = dataDirectory.resolve("employees.csv");
            writeDataset();

            CsvTableStorage storage = new CsvTableStorage(dataDirectory);
            DefaultQueryEngine serialEngine = new DefaultQueryEngine(
                    storage, AggregationExecutionConfig.serial());
            QueryResult serialReference = serialEngine.execute(QUERY);

            AggregationExecutionConfig config = configuration();
            engine = new DefaultQueryEngine(storage, config);
            QueryResult configuredResult = engine.execute(QUERY);
            assertEquivalentResults(serialReference, configuredResult);
        } catch (IOException | SqlParseException | SqlExecutionException
                 | RuntimeException | Error failure) {
            try {
                cleanUp();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        cleanUp();
    }

    @Benchmark
    public int groupedAverage() throws SqlParseException, SqlExecutionException {
        return engine.execute(QUERY).rows().size();
    }

    private AggregationExecutionConfig configuration() {
        return switch (aggregationMode) {
            case "serial" -> AggregationExecutionConfig.serial();
            case "parallel-1" -> AggregationExecutionConfig.parallel(1, BATCH_SIZE);
            case "parallel-2" -> AggregationExecutionConfig.parallel(2, BATCH_SIZE);
            case "parallel-4" -> AggregationExecutionConfig.parallel(4, BATCH_SIZE);
            case "parallel-8" -> AggregationExecutionConfig.parallel(8, BATCH_SIZE);
            default -> throw new IllegalArgumentException(
                    "Unknown aggregation mode: " + aggregationMode);
        };
    }

    private void writeDataset() throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8)) {
            writer.write("employee_id,first_name,last_name,department,job_title,salary,hire_date");
            writer.newLine();
            for (int row = 0; row < rowCount; row++) {
                writer.write("Employee");
                writer.write(Integer.toString(row + 1));
                writer.write(",Employee");
                writer.write(Integer.toString(row + 1));
                writer.write(",Record");
                writer.write(Integer.toString(row + 1));
                writer.write(",Department");
                writer.write(String.format("%02d", row % DEPARTMENT_COUNT));
                writer.write(",Synthetic Role");
                writer.write(',');
                writer.write(Long.toString(50000L + (row * 7919L % 100001)));
                writer.write(",2024-01-01");
                writer.newLine();
            }
        }
    }

    private void assertEquivalentResults(QueryResult expected, QueryResult actual) {
        Map<String, BigDecimal> expectedAverages = averagesByDepartment(expected);
        Map<String, BigDecimal> actualAverages = averagesByDepartment(actual);
        if (!expectedAverages.keySet().equals(actualAverages.keySet())) {
            throw new IllegalStateException("Aggregation result groups differ for mode "
                    + aggregationMode);
        }
        for (String department : expectedAverages.keySet()) {
            if (expectedAverages.get(department).compareTo(actualAverages.get(department)) != 0) {
                throw new IllegalStateException("Average differs for department " + department
                        + " in mode " + aggregationMode);
            }
        }
    }

    private Map<String, BigDecimal> averagesByDepartment(QueryResult result) {
        Map<String, BigDecimal> averages = new HashMap<>();
        for (Row row : result.rows()) {
            String department = row.values().get("department");
            String average = row.values().get("AVG(salary)");
            if (department == null || average == null
                    || averages.put(department, new BigDecimal(average)) != null) {
                throw new IllegalStateException("Invalid grouped aggregation result for "
                        + aggregationMode);
            }
        }
        if (averages.size() != DEPARTMENT_COUNT
                || !averages.keySet().equals(expectedDepartments())) {
            throw new IllegalStateException("Unexpected aggregation groups for " + rowCount
                    + " rows: " + averages.keySet());
        }
        return averages;
    }

    private Set<String> expectedDepartments() {
        Set<String> departments = new HashSet<>();
        for (int index = 0; index < DEPARTMENT_COUNT; index++) {
            departments.add("Department" + String.format("%02d", index));
        }
        return departments;
    }

    private void cleanUp() throws IOException {
        IOException failure = null;
        if (csvFile != null) {
            try {
                Files.deleteIfExists(csvFile);
            } catch (IOException deleteFileFailure) {
                failure = deleteFileFailure;
            }
        }
        if (dataDirectory != null) {
            try {
                Files.deleteIfExists(dataDirectory);
            } catch (IOException deleteDirectoryFailure) {
                if (failure == null) {
                    failure = deleteDirectoryFailure;
                } else {
                    failure.addSuppressed(deleteDirectoryFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
