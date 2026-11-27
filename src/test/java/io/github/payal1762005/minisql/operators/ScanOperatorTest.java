package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.model.TableSchema;
import io.github.payal1762005.minisql.storage.CsvTableStorage;
import io.github.payal1762005.minisql.storage.TableStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanOperatorTest {
    @TempDir
    Path dataDirectory;

    @Test
    void emitsRowsOneAtATimeInStorageOrder() throws IOException {
        Row first = new Row(Map.of("id", "1"));
        Row second = new Row(Map.of("id", "2"));
        ScanOperator operator = new ScanOperator(storageReturning(Stream.of(first, second)), "items");

        assertTrue(operator.hasNext());
        assertEquals(first, operator.next());
        assertTrue(operator.hasNext());
        assertEquals(second, operator.next());
        assertFalse(operator.hasNext());
    }

    @Test
    void closesStreamWhenRowsAreExhausted() throws IOException {
        AtomicBoolean closed = new AtomicBoolean();
        ScanOperator operator = new ScanOperator(
                storageReturning(Stream.of(new Row(Map.of("id", "1"))).onClose(() -> closed.set(true))),
                "items");

        operator.next();
        assertFalse(operator.hasNext());

        assertTrue(closed.get());
    }

    @Test
    void closesStreamWhenClosedExplicitly() throws IOException {
        AtomicBoolean closed = new AtomicBoolean();
        ScanOperator operator = new ScanOperator(
                storageReturning(Stream.of(new Row(Map.of("id", "1"))).onClose(() -> closed.set(true))),
                "items");

        operator.close();
        operator.close();

        assertTrue(closed.get());
        assertFalse(operator.hasNext());
    }

    @Test
    void nextThrowsWhenNoRowsRemain() throws IOException {
        ScanOperator operator = new ScanOperator(storageReturning(Stream.empty()), "items");

        assertThrows(NoSuchElementException.class, operator::next);
    }

    @Test
    void propagatesStorageFailures() {
        TableStorage storage = new TableStorage() {
            @Override
            public TableSchema getSchema(String tableName) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Stream<Row> scan(String tableName) throws IOException {
                throw new IOException("table unavailable");
            }
        };

        assertThrows(IOException.class, () -> new ScanOperator(storage, "items"));
    }

    @Test
    void consumesCsvStorageIncrementallyAndPropagatesRowErrors() throws IOException {
        Files.writeString(dataDirectory.resolve("items.csv"), "id,name\n1,Ava\n2,Noah,extra\n");
        ScanOperator operator = new ScanOperator(new CsvTableStorage(dataDirectory), "items");

        assertEquals("Ava", operator.next().values().get("name"));
        assertThrows(java.io.UncheckedIOException.class, operator::next);
        assertFalse(operator.hasNext());
    }

    private TableStorage storageReturning(Stream<Row> rows) {
        return new TableStorage() {
            @Override
            public TableSchema getSchema(String tableName) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Stream<Row> scan(String tableName) {
                return rows;
            }
        };
    }
}
