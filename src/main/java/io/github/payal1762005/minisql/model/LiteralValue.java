package io.github.payal1762005.minisql.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * A string, integer, or decimal literal in a WHERE comparison.
 */
public record LiteralValue(Type type, Object value) {
    public enum Type {
        STRING,
        INTEGER,
        DECIMAL
    }

    public LiteralValue {
        Objects.requireNonNull(type, "Literal type must not be null");
        Objects.requireNonNull(value, "Literal value must not be null");
        boolean validValue = switch (type) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof BigInteger;
            case DECIMAL -> value instanceof BigDecimal;
        };
        if (!validValue) {
            throw new IllegalArgumentException("Literal value does not match type " + type);
        }
    }
}
