package io.github.payal1762005.minisql.storage;

import io.github.payal1762005.minisql.exception.CsvStorageException;
import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.model.TableSchema;
import io.github.payal1762005.minisql.operators.ComparisonOperator;
import io.github.payal1762005.minisql.operators.ComparisonPredicate;
import io.github.payal1762005.minisql.operators.FilterOperator;
import io.github.payal1762005.minisql.operators.Operator;
import io.github.payal1762005.minisql.operators.ProjectionOperator;
import io.github.payal1762005.minisql.operators.ScanOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvTableStorageTest {
    @TempDir
    Path dataDirectory;

    @Test
    void loadsCsvRowsAndSchema() throws IOException {
        writeTable("employees.csv", """
                employee_id,department
                1001,Engineering
                1002,Sales
                """);

        CsvTableStorage storage = new CsvTableStorage(dataDirectory);

        assertEquals(new TableSchema("employees", List.of("employee_id", "department")),
                storage.getSchema("employees.csv"));
        try (var rows = storage.scan("employees")) {
            assertEquals(List.of(
                    new Row(java.util.Map.of("employee_id", "1001", "department", "Engineering")),
                    new Row(java.util.Map.of("employee_id", "1002", "department", "Sales"))),
                    rows.toList());
        }
    }

    @Test
    void mapsValuesToTheirHeaderColumns() throws IOException {
        writeTable("people.csv", "last_name,first_name\nPatel,Ava\n");

        try (var rows = new CsvTableStorage(dataDirectory).scan("people")) {
            assertEquals("Ava", rows.findFirst().orElseThrow().values().get("first_name"));
        }
    }

    @Test
    void scansOnlyRequestedColumnsInRequestedOrder() throws IOException {
        writeTable("projected.csv", "id,name,department\n1,Ava,Engineering\n");

        try (RowCursor cursor = new CsvTableStorage(dataDirectory)
                .openScan("projected", List.of("department", "name"))) {
            Row row = cursor.next();

            assertEquals(List.of("department", "name"), new ArrayList<>(row.values().keySet()));
            assertEquals("Engineering", row.values().get("department"));
            assertEquals("Ava", row.values().get("name"));
            assertFalse(cursor.hasNext());
        }
    }

    @Test
    void validatesFullCsvRowWidthDuringProjectedScan() throws IOException {
        writeTable("projected_invalid.csv", "id,name,department\n1,Ava,Engineering\n2,Noah\n");

        try (RowCursor cursor = new CsvTableStorage(dataDirectory)
                .openScan("projected_invalid", List.of("name"))) {
            assertEquals("Ava", cursor.next().values().get("name"));
            assertThrows(CsvStorageException.class, cursor::next);
            assertFalse(cursor.hasNext());
        }
    }

    @Test
    void rejectsUnknownRequestedColumn() throws IOException {
        writeTable("projected.csv", "id,name\n1,Ava\n");

        CsvStorageException exception = assertThrows(CsvStorageException.class,
                () -> new CsvTableStorage(dataDirectory).openScan("projected", List.of("missing")));

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("missing"));
    }

    @Test
    void supportsZeroColumnScanForCountStar() throws IOException {
        writeTable("count.csv", "id,name\n1,Ava\n2,Noah\n");

        try (RowCursor cursor = new CsvTableStorage(dataDirectory).openScan("count", List.of())) {
            assertEquals(new Row(java.util.Map.of()), cursor.next());
            assertEquals(new Row(java.util.Map.of()), cursor.next());
            assertFalse(cursor.hasNext());
        }
    }

    @Test
    void filtersCsvRecordsBeforeReturningProjectedRowsAndBuffersRepeatedHasNext() throws IOException {
        writeTable("predicate.csv", "id,name,salary\n1,Ava,10.00\n2,Noah,20\n3,Mia,30\n");
        List<ScanPredicate> predicates = List.of(
                new ScanPredicate("salary", ComparisonOperator.GREATER_THAN_OR_EQUAL,
                        new java.math.BigDecimal("20")),
                new ScanPredicate("id", ComparisonOperator.NOT_EQUALS, java.math.BigInteger.ONE));

        try (RowCursor cursor = new CsvTableStorage(dataDirectory)
                .openScan("predicate", List.of("name"), predicates)) {
            assertEquals(true, cursor.hasNext());
            assertEquals(true, cursor.hasNext());
            assertEquals("Noah", cursor.next().values().get("name"));
            assertEquals(true, cursor.hasNext());
            assertEquals("Mia", cursor.next().values().get("name"));
            assertEquals(false, cursor.hasNext());
        }
    }

    @Test
    void pushedResultsMatchTheExistingFilterAndProjectionPipeline() throws IOException {
        writeTable("equivalence.csv", "id,name,salary\n1,Ava,10.00\n2,Noah,20\n3,Mia,30\n");
        var storage = new CsvTableStorage(dataDirectory);
        List<ScanPredicate> pushedPredicates = List.of(
                new ScanPredicate("salary", ComparisonOperator.GREATER_THAN_OR_EQUAL,
                        new java.math.BigDecimal("20")),
                new ScanPredicate("id", ComparisonOperator.NOT_EQUALS, java.math.BigInteger.ONE));
        Operator pushed = new ScanOperator(storage, "equivalence", List.of("name"), pushedPredicates);

        Operator baseline = new ProjectionOperator(
                new FilterOperator(new ScanOperator(storage, "equivalence"), row ->
                        new ComparisonPredicate("salary", ComparisonOperator.GREATER_THAN_OR_EQUAL,
                                new java.math.BigDecimal("20")).test(row)
                                && new ComparisonPredicate("id", ComparisonOperator.NOT_EQUALS,
                                java.math.BigInteger.ONE).test(row)),
                List.of("name"));

        assertEquals(consume(baseline), consume(pushed));
    }

    @Test
    void pushedNumericComparisonsMatchComparisonPredicateSemantics() throws IOException {
        writeTable("comparison.csv", "amount\n10.50\n");
        var storage = new CsvTableStorage(dataDirectory);
        Row row = new Row(java.util.Map.of("amount", "10.50"));

        for (ComparisonOperator operator : ComparisonOperator.values()) {
            ScanPredicate pushed = new ScanPredicate("amount", operator,
                    new java.math.BigDecimal("10.5"));
            boolean expected = new io.github.payal1762005.minisql.operators.ComparisonPredicate(
                    "amount", operator, new java.math.BigDecimal("10.5")).test(row);
            try (RowCursor cursor = storage.openScan("comparison", List.of("amount"), List.of(pushed))) {
                assertEquals(expected, cursor.hasNext(), operator.toString());
            }
        }
    }

    @Test
    void invalidNumericValueFailsAndClosesPushedScan() throws IOException {
        writeTable("invalid_number.csv", "amount\ninvalid\n");
        RowCursor cursor = new CsvTableStorage(dataDirectory).openScan("invalid_number",
                List.of("amount"), List.of(new ScanPredicate(
                        "amount", ComparisonOperator.GREATER_THAN, 1)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, cursor::hasNext);

        assertTrue(exception.getCause() instanceof NumberFormatException);
        assertFalse(cursor.hasNext());
        cursor.close();
    }

    @Test
    void pushedScanHandlesEmptyInputAndClosesOnExhaustion() throws IOException {
        writeTable("empty_rows.csv", "id,name\n");
        RowCursor cursor = new CsvTableStorage(dataDirectory).openScan("empty_rows", List.of("name"),
                List.of(new ScanPredicate("id", ComparisonOperator.EQUALS, 1)));

        assertFalse(cursor.hasNext());
        cursor.close();
        assertFalse(cursor.hasNext());
    }

    @Test
    void explicitlyClosingPushedScanIsIdempotent() throws IOException {
        Path table = writeTable("close_pushed.csv", "id,name\n1,Ava\n2,Noah\n");
        RowCursor cursor = new CsvTableStorage(dataDirectory).openScan("close_pushed", List.of("name"),
                List.of(new ScanPredicate("id", ComparisonOperator.GREATER_THAN, 0)));

        assertTrue(cursor.hasNext());
        cursor.close();
        cursor.close();

        assertFalse(cursor.hasNext());
        Files.delete(table);
    }

    @Test
    void rejectsEmptyCsvFile() throws IOException {
        writeTable("empty.csv", "");

        assertThrows(CsvStorageException.class,
                () -> new CsvTableStorage(dataDirectory).getSchema("empty"));
    }

    @Test
    void reportsMissingCsvFile() {
        assertThrows(CsvStorageException.class,
                () -> new CsvTableStorage(dataDirectory).scan("missing"));
    }

    @Test
    void rejectsRowsWithDifferentFieldCount() throws IOException {
        writeTable("invalid.csv", "id,name\n1,Ava,extra\n");

        try (var rows = new CsvTableStorage(dataDirectory).scan("invalid")) {
            assertThrows(java.io.UncheckedIOException.class, rows::toList);
        }
    }

    @Test
    void parsesQuotedFieldsAndEscapedQuotes() throws IOException {
        writeTable("quoted.csv", "id,job_title\n1,\"Engineer, Data\"\n2,\"Senior \"\"Platform\"\" Engineer\"\n");

        try (var rows = new CsvTableStorage(dataDirectory).scan("quoted")) {
            assertEquals(List.of("Engineer, Data", "Senior \"Platform\" Engineer"),
                    rows.map(row -> row.values().get("job_title")).toList());
        }
    }

    @Test
    void rejectsMalformedQuotedInput() throws IOException {
        writeTable("malformed.csv", "id,name\n1,\"Ava\n");

        try (var rows = new CsvTableStorage(dataDirectory).scan("malformed")) {
            assertThrows(java.io.UncheckedIOException.class, rows::toList);
        }
    }

    @Test
    void readsAndValidatesRecordsLazily() throws IOException {
        writeTable("lazy.csv", "id,name\n1,Ava\n2,Noah,extra\n");
        var cursor = new CsvTableStorage(dataDirectory).openScan("lazy");

        assertEquals("Ava", cursor.next().values().get("name"));
        assertThrows(CsvStorageException.class, cursor::next);
        assertFalse(cursor.hasNext());
    }

    @Test
    void closingCursorClosesFileAndPreventsFurtherReads() throws IOException {
        Path table = writeTable("close.csv", "id,name\n1,Ava\n");
        var cursor = new CsvTableStorage(dataDirectory).openScan("close");

        cursor.close();

        assertFalse(cursor.hasNext());
        assertThrows(java.util.NoSuchElementException.class, cursor::next);
        Files.delete(table);
    }

    @Test
    void exhaustingCursorClosesFileAutomatically() throws IOException {
        Path table = writeTable("exhaust.csv", "id,name\n1,Ava\n");
        var cursor = new CsvTableStorage(dataDirectory).openScan("exhaust");

        assertEquals("Ava", cursor.next().values().get("name"));
        assertFalse(cursor.hasNext());
        Files.delete(table);
    }

    @Test
    void schemaReadDoesNotParseDataRecords() throws IOException {
        writeTable("header.csv", "id,name\n1,\"unterminated\n");

        assertEquals(new TableSchema("header", List.of("id", "name")),
                new CsvTableStorage(dataDirectory).getSchema("header"));
    }

    private Path writeTable(String name, String contents) throws IOException {
        Path file = dataDirectory.resolve(name);
        Files.writeString(file, contents);
        return file;
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
}
