package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.storage.RowCursor;
import io.github.payal1762005.minisql.storage.ScanPredicate;
import io.github.payal1762005.minisql.storage.TableStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Produces rows from a table in storage, one row at a time.
 */
public class ScanOperator implements Operator {
    private final RowCursor cursor;
    private boolean closed;

    public ScanOperator(TableStorage storage, String tableName) throws IOException {
        Objects.requireNonNull(storage, "storage must not be null");
        this.cursor = Objects.requireNonNull(storage.openScan(tableName),
                "storage returned a null row cursor");
    }

    public ScanOperator(TableStorage storage, String tableName, List<String> columns) throws IOException {
        Objects.requireNonNull(storage, "storage must not be null");
        this.cursor = Objects.requireNonNull(storage.openScan(tableName, columns),
                "storage returned a null row cursor");
    }

    public ScanOperator(
            TableStorage storage, String tableName, List<String> columns,
            List<ScanPredicate> predicates) throws IOException {
        Objects.requireNonNull(storage, "storage must not be null");
        this.cursor = Objects.requireNonNull(storage.openScan(tableName, columns, predicates),
                "storage returned a null row cursor");
    }

    @Override
    public boolean hasNext() {
        if (closed) {
            return false;
        }

        try {
            if (cursor.hasNext()) {
                return true;
            }
            close();
            return false;
        } catch (IOException exception) {
            UncheckedIOException failure = new UncheckedIOException(exception);
            closeAfterFailure(failure);
            throw failure;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public Row next() {
        if (!hasNext()) {
            throw new NoSuchElementException("No more rows in table scan");
        }

        try {
            return cursor.next();
        } catch (IOException exception) {
            UncheckedIOException failure = new UncheckedIOException(exception);
            closeAfterFailure(failure);
            throw failure;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                cursor.close();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private void closeAfterFailure(Throwable failure) {
        try {
            close();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
