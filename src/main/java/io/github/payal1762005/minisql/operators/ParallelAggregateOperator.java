package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Single-consumer parallel hash aggregation. The calling thread alone pulls
 * the child; workers aggregate bounded batches without accessing the operator.
 */
final class ParallelAggregateOperator implements Operator {
    private final Operator child;
    private final List<String> groupByColumns;
    private final List<AggregateSpecification> specifications;
    private final int batchSize;
    private final int maxInFlightBatches;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final Deque<Future<PartialAggregation>> pending = new ArrayDeque<>();

    private Iterator<Row> results = List.<Row>of().iterator();
    private boolean aggregated;
    private boolean childClosed;
    private boolean closed;
    private PartialAggregation merged;

    ParallelAggregateOperator(
            Operator child,
            List<String> groupByColumns,
            List<AggregateSpecification> specifications,
            int batchSize,
            int maxInFlightBatches) {
        this(child, groupByColumns, specifications, batchSize, maxInFlightBatches,
                createOwnedExecutor(child, groupByColumns, specifications,
                        batchSize, maxInFlightBatches), true);
    }

    ParallelAggregateOperator(
            Operator child,
            List<String> groupByColumns,
            List<AggregateSpecification> specifications,
            int batchSize,
            int maxInFlightBatches,
            ExecutorService executor) {
        this(child, groupByColumns, specifications, batchSize, maxInFlightBatches,
                executor, false);
    }

    ParallelAggregateOperator(
            Operator child,
            List<String> groupByColumns,
            List<AggregateSpecification> specifications,
            int batchSize,
            int maxInFlightBatches,
            ExecutorService executor,
            boolean ownsExecutor) {
        this.child = Objects.requireNonNull(child, "child operator must not be null");
        this.groupByColumns = List.copyOf(
                Objects.requireNonNull(groupByColumns, "Group-by columns must not be null"));
        this.specifications = List.copyOf(
                Objects.requireNonNull(specifications, "Aggregate specifications must not be null"));
        GroupAccumulator.validateConfiguration(this.groupByColumns, this.specifications);
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be greater than zero");
        }
        if (maxInFlightBatches <= 0) {
            throw new IllegalArgumentException("Maximum in-flight batches must be greater than zero");
        }
        this.batchSize = batchSize;
        this.maxInFlightBatches = maxInFlightBatches;
        this.executor = Objects.requireNonNull(executor, "Executor must not be null");
        this.ownsExecutor = ownsExecutor;
    }

    @Override
    public boolean hasNext() {
        if (closed) {
            return false;
        }
        try {
            aggregateIfNeeded();
            if (results.hasNext()) {
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
            throw new NoSuchElementException("No aggregate result rows remain");
        }
        return results.next();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        results = List.<Row>of().iterator();
        pending.forEach(future -> future.cancel(true));
        pending.clear();
        Throwable failure = null;
        try {
            closeChild();
        } catch (RuntimeException | Error closeFailure) {
            failure = closeFailure;
        }
        if (ownsExecutor) {
            try {
                executor.shutdownNow();
            } catch (RuntimeException | Error shutdownFailure) {
                if (failure == null) {
                    failure = shutdownFailure;
                } else {
                    failure.addSuppressed(shutdownFailure);
                }
            }
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void aggregateIfNeeded() {
        if (aggregated) {
            return;
        }

        try {
            merged = new PartialAggregation(groupByColumns, specifications);
            produceBatches();
            closeChild();
            while (!pending.isEmpty()) {
                mergeNextBatch();
            }
            results = merged.resultRows().iterator();
            aggregated = true;
            merged = null;
            shutdownOwnedExecutor();
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    private void produceBatches() {
        boolean exhausted = false;
        while (!exhausted) {
            List<Row> batch = new ArrayList<>(batchSize);
            while (batch.size() < batchSize) {
                if (!child.hasNext()) {
                    exhausted = true;
                    break;
                }
                batch.add(Objects.requireNonNull(
                        child.next(), "Child operator returned a null row"));
            }
            if (!batch.isEmpty()) {
                List<Row> immutableBatch = List.copyOf(batch);
                pending.addLast(executor.submit(() ->
                        new PartialAggregation(immutableBatch, groupByColumns, specifications)));
            }
            if (pending.size() >= maxInFlightBatches) {
                mergeNextBatch();
            }
        }
    }

    private void mergeNextBatch() {
        merged.merge(await(pending.removeFirst()));
    }

    private PartialAggregation await(Future<PartialAggregation> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for aggregate batch", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Aggregate batch failed", cause);
        }
    }

    private void closeChild() {
        if (!childClosed) {
            childClosed = true;
            child.close();
        }
    }

    private void shutdownOwnedExecutor() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }

    private static ExecutorService createOwnedExecutor(
            Operator child,
            List<String> groupByColumns,
            List<AggregateSpecification> specifications,
            int batchSize,
            int maxInFlightBatches) {
        Objects.requireNonNull(child, "child operator must not be null");
        List<String> groups = List.copyOf(
                Objects.requireNonNull(groupByColumns, "Group-by columns must not be null"));
        List<AggregateSpecification> specs = List.copyOf(
                Objects.requireNonNull(specifications, "Aggregate specifications must not be null"));
        GroupAccumulator.validateConfiguration(groups, specs);
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be greater than zero");
        }
        if (maxInFlightBatches <= 0) {
            throw new IllegalArgumentException("Maximum in-flight batches must be greater than zero");
        }
        return Executors.newFixedThreadPool(maxInFlightBatches);
    }

    private void closeAfterFailure(Throwable failure) {
        try {
            close();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
