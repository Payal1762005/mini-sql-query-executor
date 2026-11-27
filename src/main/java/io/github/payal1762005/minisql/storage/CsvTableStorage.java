package io.github.payal1762005.minisql.storage;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import io.github.payal1762005.minisql.exception.CsvStorageException;
import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.model.TableSchema;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Reads CSV tables from a configured data directory.
 */
public class CsvTableStorage implements TableStorage {
    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setIgnoreEmptyLines(false)
            .get();

    private final Path dataDirectory;

    public CsvTableStorage() {
        this(Path.of("src", "main", "resources", "data"));
    }

    public CsvTableStorage(Path dataDirectory) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    }

    @Override
    public TableSchema getSchema(String tableName) throws IOException {
        Path csvPath = resolveTablePath(tableName);
        if (!Files.isRegularFile(csvPath)) {
            throw new CsvStorageException("CSV table file does not exist: " + csvPath);
        }
        try (Reader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
             CSVParser parser = CSV_FORMAT.parse(reader)) {
            var records = parser.iterator();
            if (!records.hasNext()) {
                throw new CsvStorageException("CSV table is empty and has no header: " + csvPath);
            }
            return schema(csvPath, validateHeader(records.next(), csvPath));
        } catch (CsvStorageException exception) {
            throw exception;
        } catch (IOException | IllegalStateException | UncheckedIOException exception) {
            throw new CsvStorageException("Unable to read CSV table header: " + csvPath, exception);
        }
    }

    @Override
    public Stream<Row> scan(String tableName) throws IOException {
        RowCursor cursor = openScan(tableName);
        var spliterator = new Spliterators.AbstractSpliterator<Row>(Long.MAX_VALUE,
                Spliterator.ORDERED | Spliterator.NONNULL) {
            @Override
            public boolean tryAdvance(java.util.function.Consumer<? super Row> action) {
                try {
                    if (!cursor.hasNext()) {
                        cursor.close();
                        return false;
                    }
                    action.accept(cursor.next());
                    return true;
                } catch (IOException exception) {
                    closeAfterFailure(cursor, exception);
                    throw new UncheckedIOException(exception);
                } catch (RuntimeException | Error failure) {
                    closeAfterFailure(cursor, failure);
                    throw failure;
                }
            }
        };
        return StreamSupport.stream(spliterator, false).onClose(() -> closeStreamCursor(cursor));
    }

    @Override
    public RowCursor openScan(String tableName) throws IOException {
        return openScan(tableName, null, false, List.of());
    }

    @Override
    public RowCursor openScan(String tableName, List<String> requestedColumns) throws IOException {
        return openScan(tableName, requestedColumns, List.of());
    }

    @Override
    public RowCursor openScan(
            String tableName, List<String> requestedColumns, List<ScanPredicate> predicates) throws IOException {
        if (requestedColumns == null) {
            throw new IllegalArgumentException("Scan columns must not be null");
        }
        if (predicates == null) {
            throw new IllegalArgumentException("Scan predicates must not be null");
        }
        List<String> columns = List.copyOf(requestedColumns);
        List<ScanPredicate> scanPredicates = List.copyOf(predicates);
        if (columns.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Scan columns must not be blank");
        }
        if (new HashSet<>(columns).size() != columns.size()) {
            throw new IllegalArgumentException("Scan columns must not contain duplicates");
        }
        return openScan(tableName, columns, true, scanPredicates);
    }

    private RowCursor openScan(
            String tableName, List<String> requestedColumns, boolean projectionRequested,
            List<ScanPredicate> predicates) throws IOException {
        Path csvPath = resolveTablePath(tableName);
        if (!Files.isRegularFile(csvPath)) {
            throw new CsvStorageException("CSV table file does not exist: " + csvPath);
        }

        Reader reader = null;
        CSVParser parser = null;
        try {
            reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
            parser = CSV_FORMAT.parse(reader);
            var records = parser.iterator();
            if (!records.hasNext()) {
                throw new CsvStorageException("CSV table is empty and has no header: " + csvPath);
            }
            List<String> columns = validateHeader(records.next(), csvPath);
            List<String> scanColumns = projectionRequested ? requestedColumns : columns;
            List<Integer> scanIndexes = new ArrayList<>(scanColumns.size());
            for (String column : scanColumns) {
                int columnIndex = columns.indexOf(column);
                if (columnIndex < 0) {
                    throw new CsvStorageException("Requested scan column '" + column
                            + "' does not exist in table: " + csvPath);
                }
                scanIndexes.add(columnIndex);
            }
            List<Integer> predicateIndexes = new ArrayList<>(predicates.size());
            for (ScanPredicate predicate : predicates) {
                int columnIndex = columns.indexOf(predicate.columnName());
                if (columnIndex < 0) {
                    throw new CsvStorageException("Predicate column '" + predicate.columnName()
                            + "' does not exist in table: " + csvPath);
                }
                predicateIndexes.add(columnIndex);
            }
            return new CsvRowCursor(reader, parser, records, columns, scanColumns,
                    scanIndexes, predicates, predicateIndexes, csvPath);
        } catch (CsvStorageException exception) {
            closeResources(parser, reader, exception);
            throw exception;
        } catch (IOException | IllegalStateException | UncheckedIOException exception) {
            CsvStorageException storageException =
                    new CsvStorageException("Unable to initialize CSV scan: " + csvPath, exception);
            closeResources(parser, reader, storageException);
            throw storageException;
        }
    }

    private TableSchema schema(Path csvPath, List<String> columns) {
        String resolvedTableName = csvPath.getFileName().toString();
        resolvedTableName = resolvedTableName.substring(0,
                resolvedTableName.length() - ".csv".length());
        return new TableSchema(resolvedTableName, columns);
    }

    private List<String> validateHeader(CSVRecord header, Path csvPath) throws CsvStorageException {
        if (header.size() == 0) {
            throw new CsvStorageException("CSV table has no column headers: " + csvPath);
        }

        List<String> columns = new ArrayList<>(header.size());
        Set<String> uniqueColumns = new HashSet<>();
        for (String column : header) {
            if (column.isBlank()) {
                throw new CsvStorageException("CSV table has a blank column header: " + csvPath);
            }
            if (!uniqueColumns.add(column)) {
                throw new CsvStorageException("CSV table has a duplicate column header '"
                        + column + "': " + csvPath);
            }
            columns.add(column);
        }
        return List.copyOf(columns);
    }

    private Path resolveTablePath(String tableName) throws CsvStorageException {
        if (tableName == null || tableName.isBlank()) {
            throw new CsvStorageException("Table name must not be blank");
        }
        Path requestedPath = Path.of(tableName);
        if (requestedPath.isAbsolute() || requestedPath.getNameCount() != 1
                || tableName.contains("/") || tableName.contains("\\")) {
            throw new CsvStorageException("Table name must be a file name, not a path: " + tableName);
        }

        String fileName = tableName.endsWith(".csv") ? tableName : tableName + ".csv";
        Path csvPath = dataDirectory.resolve(fileName).normalize();
        if (!csvPath.startsWith(dataDirectory)) {
            throw new CsvStorageException("Table name resolves outside the data directory: " + tableName);
        }
        return csvPath;
    }

    private static void closeResources(CSVParser parser, Reader reader, Throwable failure) {
        if (parser != null) {
            try {
                parser.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private static void closeAfterFailure(RowCursor cursor, Throwable failure) {
        try {
            cursor.close();
        } catch (IOException | RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeStreamCursor(RowCursor cursor) {
        try {
            cursor.close();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static final class CsvRowCursor implements RowCursor {
        private final Reader reader;
        private final CSVParser parser;
        private final java.util.Iterator<CSVRecord> records;
        private final List<String> columns;
        private final List<String> scanColumns;
        private final List<Integer> scanIndexes;
        private final List<ScanPredicate> predicates;
        private final List<Integer> predicateIndexes;
        private final Path csvPath;
        private CSVRecord bufferedRecord;
        private boolean closed;

        private CsvRowCursor(Reader reader, CSVParser parser,
                             java.util.Iterator<CSVRecord> records,
                             List<String> columns, List<String> scanColumns,
                             List<Integer> scanIndexes, List<ScanPredicate> predicates,
                             List<Integer> predicateIndexes, Path csvPath) {
            this.reader = reader;
            this.parser = parser;
            this.records = records;
            this.columns = columns;
            this.scanColumns = scanColumns;
            this.scanIndexes = scanIndexes;
            this.predicates = predicates;
            this.predicateIndexes = predicateIndexes;
            this.csvPath = csvPath;
        }

        @Override
        public boolean hasNext() throws IOException {
            if (closed) {
                return false;
            }
            if (bufferedRecord != null) {
                return true;
            }
            try {
                while (records.hasNext()) {
                    CSVRecord record = records.next();
                    validateRecordLength(record);
                    boolean matches = true;
                    for (int index = 0; index < predicates.size(); index++) {
                        if (!predicates.get(index).testValue(record.get(predicateIndexes.get(index)))) {
                            matches = false;
                            break;
                        }
                    }
                    if (matches) {
                        bufferedRecord = record;
                        return true;
                    }
                }
                close();
                return false;
            } catch (UncheckedIOException exception) {
                throw fail("Unable to read CSV table: " + csvPath, exception.getCause());
            } catch (IllegalStateException exception) {
                throw fail("Malformed CSV table: " + csvPath, exception);
            } catch (CsvStorageException exception) {
                closeAfterFailure(this, exception);
                throw exception;
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(this, failure);
                throw failure;
            }
        }

        @Override
        public Row next() throws IOException {
            if (!hasNext()) {
                throw new NoSuchElementException("No more rows in CSV table: " + csvPath);
            }

            try {
                CSVRecord record = bufferedRecord;
                bufferedRecord = null;
                var values = new LinkedHashMap<String, String>();
                for (int index = 0; index < scanColumns.size(); index++) {
                    values.put(scanColumns.get(index), record.get(scanIndexes.get(index)));
                }
                return new Row(values);
            } catch (UncheckedIOException exception) {
                throw fail("Unable to read CSV table: " + csvPath, exception.getCause());
            } catch (IllegalStateException exception) {
                throw fail("Malformed CSV table: " + csvPath, exception);
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(this, failure);
                throw failure;
            }
        }

        private void validateRecordLength(CSVRecord record) throws CsvStorageException {
            if (record.size() != columns.size()) {
                throw new CsvStorageException("Invalid row length in " + csvPath
                        + " at record " + record.getRecordNumber() + ": expected "
                        + columns.size() + " fields but found " + record.size());
            }
        }

        private CsvStorageException fail(String message, Throwable cause) {
            CsvStorageException exception = new CsvStorageException(message, cause);
            closeAfterFailure(this, exception);
            return exception;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            bufferedRecord = null;
            IOException failure = null;
            try {
                parser.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                reader.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw new CsvStorageException("Unable to close CSV scan: " + csvPath, failure);
            }
        }
    }
}
