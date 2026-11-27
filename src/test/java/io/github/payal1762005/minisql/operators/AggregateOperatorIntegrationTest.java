package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.storage.CsvTableStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AggregateOperatorIntegrationTest {
    @TempDir
    Path dataDirectory;

    @Test
    void aggregatesRowsFilteredFromCsvScan() throws IOException {
        Files.writeString(dataDirectory.resolve("employees.csv"), """
                department,salary
                Engineering,100
                Engineering,150
                Sales,80
                """);

        ScanOperator scan = new ScanOperator(new CsvTableStorage(dataDirectory), "employees");
        FilterOperator filter = new FilterOperator(scan,
                new ComparisonPredicate("salary", ComparisonOperator.GREATER_THAN, 90));
        AggregateOperator aggregate = new AggregateOperator(filter, List.of("department"),
                AggregateSpecification.countAll("employees"),
                new AggregateSpecification(AggregateFunction.AVG, "salary", "average_salary"));

        List<Row> results = new ArrayList<>();
        try (aggregate) {
            while (aggregate.hasNext()) {
                results.add(aggregate.next());
            }
        }

        assertEquals(List.of(
                new Row(Map.of("department", "Engineering", "employees", "2",
                        "average_salary", "125.0000"))), results);
    }
}
