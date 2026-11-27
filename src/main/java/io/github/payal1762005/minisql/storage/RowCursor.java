package io.github.payal1762005.minisql.storage;

import io.github.payal1762005.minisql.model.Row;

import java.io.IOException;
import java.util.NoSuchElementException;

/**
 * A closeable, forward-only source of table rows.
 */
public interface RowCursor extends AutoCloseable {
    boolean hasNext() throws IOException;

    /**
     * @throws NoSuchElementException when no rows remain
     */
    Row next() throws IOException;

    @Override
    void close() throws IOException;
}
