package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Lazily projects each child row onto an ordered set of columns.
 */
public class ProjectionOperator implements Operator {
    private final Operator child;
    private final List<String> columns;
    private final List<String> outputNames;
    private boolean closed;

    public ProjectionOperator(Operator child, List<String> columns) {
        this(child, columns, columns);
    }

    public ProjectionOperator(Operator child, List<String> columns, List<String> outputNames) {
        this.child = Objects.requireNonNull(child, "child operator must not be null");
        Objects.requireNonNull(columns, "Projection columns must not be null");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Projection must contain at least one column");
        }
        Objects.requireNonNull(outputNames, "Projection output names must not be null");
        if (columns.size() != outputNames.size()) {
            throw new IllegalArgumentException("Projection columns and output names must have the same size");
        }

        List<String> validatedColumns = new ArrayList<>(columns.size());
        for (String column : columns) {
            if (column == null || column.isBlank()) {
                throw new IllegalArgumentException("Projection column must not be blank");
            }
            validatedColumns.add(column);
        }
        List<String> validatedOutputNames = new ArrayList<>(outputNames.size());
        Set<String> uniqueOutputNames = new HashSet<>();
        for (String outputName : outputNames) {
            if (outputName == null || outputName.isBlank()) {
                throw new IllegalArgumentException("Projection output name must not be blank");
            }
            if (!uniqueOutputNames.add(outputName)) {
                throw new IllegalArgumentException("Duplicate projection output name: " + outputName);
            }
            validatedOutputNames.add(outputName);
        }
        this.columns = List.copyOf(validatedColumns);
        this.outputNames = List.copyOf(validatedOutputNames);
    }

    @Override
    public boolean hasNext() {
        if (closed) {
            return false;
        }

        try {
            if (child.hasNext()) {
                return true;
            }
            close();
            return false;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public Row next() {
        if (!hasNext()) {
            throw new NoSuchElementException("No more rows to project");
        }

        try {
            Row input = Objects.requireNonNull(child.next(), "Child operator returned a null row");
            var projectedValues = new LinkedHashMap<String, String>();
            for (int index = 0; index < columns.size(); index++) {
                String column = columns.get(index);
                if (!input.values().containsKey(column)) {
                    throw new IllegalArgumentException(
                            "Projection column '" + column + "' is missing from row");
                }
                projectedValues.put(outputNames.get(index), input.values().get(column));
            }
            return new Row(projectedValues);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            child.close();
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
