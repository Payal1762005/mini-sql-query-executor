package io.github.payal1762005.minisql.storage;

import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.model.TableSchema;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Provides table metadata and a closeable stream of table rows.
 */
public interface TableStorage {
    TableSchema getSchema(String tableName) throws IOException;

    Stream<Row> scan(String tableName) throws IOException;

    /**
     * Scans only the requested columns, preserving their order. Implementations
     * may override this method to avoid reading unrequested storage columns.
     */
    default Stream<Row> scan(String tableName, List<String> columns) throws IOException {
        RowCursor cursor = openScan(tableName, columns);
        var spliterator = new java.util.Spliterators.AbstractSpliterator<Row>(
                Long.MAX_VALUE, java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL) {
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
                    try {
                        cursor.close();
                    } catch (IOException closeFailure) {
                        exception.addSuppressed(closeFailure);
                    }
                    throw new java.io.UncheckedIOException(exception);
                } catch (RuntimeException | Error failure) {
                    try {
                        cursor.close();
                    } catch (IOException | RuntimeException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
            }
        };
        return java.util.stream.StreamSupport.stream(spliterator, false).onClose(() -> {
            try {
                cursor.close();
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        });
    }

    /**
     * Opens a resource-managed row cursor. Implementations should override this
     * method when they can provide genuinely incremental reads.
     */
    default RowCursor openScan(String tableName) throws IOException {
        Stream<Row> rows = Objects.requireNonNull(scan(tableName),
                "storage returned a null row stream");
        Iterator<Row> iterator = rows.iterator();
        return new RowCursor() {
            private boolean closed;

            @Override
            public boolean hasNext() {
                if (closed) {
                    return false;
                }
                if (!iterator.hasNext()) {
                    close();
                    return false;
                }
                return true;
            }

            @Override
            public Row next() {
                if (!hasNext()) {
                    throw new NoSuchElementException("No more rows in table scan");
                }
                return iterator.next();
            }

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    rows.close();
                }
            }
        };
    }

    /**
     * Opens a scan restricted to the requested columns. The default adapts
     * existing full-row implementations; storage engines can override it for
     * actual column-level reads.
     */
    default RowCursor openScan(String tableName, List<String> columns) throws IOException {
        Objects.requireNonNull(columns, "Scan columns must not be null");
        List<String> requestedColumns = List.copyOf(columns);
        if (requestedColumns.stream().anyMatch(column -> column.isBlank())) {
            throw new IllegalArgumentException("Scan columns must not be blank");
        }
        if (new java.util.HashSet<>(requestedColumns).size() != requestedColumns.size()) {
            throw new IllegalArgumentException("Scan columns must not contain duplicates");
        }

        RowCursor source = openScan(tableName);
        return new RowCursor() {
            private boolean closed;

            @Override
            public boolean hasNext() throws IOException {
                if (closed) {
                    return false;
                }
                if (source.hasNext()) {
                    return true;
                }
                close();
                return false;
            }

            @Override
            public Row next() throws IOException {
                if (!hasNext()) {
                    throw new NoSuchElementException("No more rows in table scan");
                }
                try {
                    Row sourceRow = source.next();
                    var values = new LinkedHashMap<String, String>();
                    for (String column : requestedColumns) {
                        if (!sourceRow.values().containsKey(column)) {
                            throw new IllegalArgumentException(
                                    "Scan column '" + column + "' is missing from row");
                        }
                        values.put(column, sourceRow.values().get(column));
                    }
                    return new Row(values);
                } catch (RuntimeException | Error failure) {
                    try {
                        close();
                    } catch (IOException | RuntimeException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
            }

            @Override
            public void close() throws IOException {
                if (!closed) {
                    closed = true;
                    source.close();
                }
            }
        };
    }

    /**
     * Opens a projected scan with simple predicates. Implementations can evaluate
     * predicates before constructing rows; the default preserves compatibility
     * by filtering rows returned by the existing projected scan.
     */
    default RowCursor openScan(
            String tableName, List<String> columns, List<ScanPredicate> predicates) throws IOException {
        Objects.requireNonNull(columns, "Scan columns must not be null");
        Objects.requireNonNull(predicates, "Scan predicates must not be null");
        List<String> outputColumns = List.copyOf(columns);
        List<ScanPredicate> scanPredicates = List.copyOf(predicates);
        var sourceColumns = new java.util.LinkedHashSet<>(outputColumns);
        scanPredicates.forEach(predicate -> sourceColumns.add(predicate.columnName()));
        RowCursor source = openScan(tableName, List.copyOf(sourceColumns));
        return new RowCursor() {
            private Row bufferedRow;
            private boolean hasBufferedRow;
            private boolean closed;

            @Override
            public boolean hasNext() throws IOException {
                if (closed) {
                    return false;
                }
                if (hasBufferedRow) {
                    return true;
                }
                try {
                    while (source.hasNext()) {
                        Row candidate = source.next();
                        boolean matches = true;
                        for (ScanPredicate predicate : scanPredicates) {
                            if (!predicate.test(candidate)) {
                                matches = false;
                                break;
                            }
                        }
                        if (matches) {
                            if (candidate.values().keySet().equals(
                                    new java.util.LinkedHashSet<>(outputColumns))) {
                                bufferedRow = candidate;
                            } else {
                                var outputValues = new LinkedHashMap<String, String>();
                                for (String column : outputColumns) {
                                    if (!candidate.values().containsKey(column)) {
                                        throw new IllegalArgumentException(
                                                "Scan column '" + column + "' is missing from row");
                                    }
                                    outputValues.put(column, candidate.values().get(column));
                                }
                                bufferedRow = new Row(outputValues);
                            }
                            hasBufferedRow = true;
                            return true;
                        }
                    }
                    close();
                    return false;
                } catch (IOException | RuntimeException | Error failure) {
                    closeAfterFailure(this, failure);
                    throw failure;
                }
            }

            @Override
            public Row next() throws IOException {
                if (!hasNext()) {
                    throw new NoSuchElementException("No more rows satisfy the scan predicates");
                }
                Row result = bufferedRow;
                bufferedRow = null;
                hasBufferedRow = false;
                return result;
            }

            @Override
            public void close() throws IOException {
                if (!closed) {
                    closed = true;
                    bufferedRow = null;
                    hasBufferedRow = false;
                    source.close();
                }
            }
        };
    }

    private static void closeAfterFailure(RowCursor cursor, Throwable failure) {
        try {
            cursor.close();
        } catch (IOException | RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
