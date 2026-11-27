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

class ComparisonPredicateIntegrationTest {
    @TempDir
    Path dataDirectory;

    @Test
    void filtersCsvScanRowsUsingNumericAndStringPredicates() throws IOException {
        Files.writeString(dataDirectory.resolve("employees.csv"), """
                name,salary,department
                Ava,100,Engineering
                Noah,20,Engineering
                Mia,125.50,Sales
                """);

        ScanOperator scan = new ScanOperator(new CsvTableStorage(dataDirectory), "employees");
        FilterOperator salaryFilter = new FilterOperator(scan,
                new ComparisonPredicate("salary", ComparisonOperator.GREATER_THAN, new BigDecimal("20")));
        FilterOperator departmentFilter = new FilterOperator(salaryFilter,
                new ComparisonPredicate("department", ComparisonOperator.EQUALS, "Engineering"));

        List<Row> result = new ArrayList<>();
        try (departmentFilter) {
            while (departmentFilter.hasNext()) {
                result.add(departmentFilter.next());
            }
        }

        assertEquals(List.of(
                new Row(Map.of("name", "Ava", "salary", "100", "department", "Engineering"))),
                result);
    }
}
