package io.github.payal1762005.minisql.exception;

import java.io.IOException;

/**
 * Raised when a CSV table cannot be located or parsed.
 */
public class CsvStorageException extends IOException {
    public CsvStorageException(String message) {
        super(message);
    }

    public CsvStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
