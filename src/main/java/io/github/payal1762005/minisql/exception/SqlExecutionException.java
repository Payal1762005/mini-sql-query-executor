package io.github.payal1762005.minisql.exception;

/**
 * Raised when a query cannot be executed.
 */
public class SqlExecutionException extends Exception {
    public SqlExecutionException(String message) {
        super(message);
    }

    public SqlExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
