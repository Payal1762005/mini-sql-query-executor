package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelAggregateOperatorTest {
    @Test
    void mergesOverlappingGroupsAcrossBatchesIdenticallyToSequentialAggregation() {
        List<Row> input = List.of(
                row("department", "Sales", "amount", "0.10"),
                row("department", "Engineering", "amount", "2"),
                row("department", "Sales", "amount", "0.20"),
                row("department", "Engineering", "amount", "4"));
        List<AggregateSpecification> specs = List.of(
                AggregateSpecification.countAll("rows"),
                spec(AggregateFunction.COUNT, "amount", "non_null"),
                spec(AggregateFunction.SUM, "amount", "total"),
                spec(AggregateFunction.AVG, "amount", "average"),
                spec(AggregateFunction.MIN, "amount", "minimum"),
                spec(AggregateFunction.MAX, "amount", "maximum"));

        assertEquals(sequential(input, List.of("department"), specs),
                parallel(input, List.of("department"), specs, 1, 2));
    }

    @Test
    void preservesFirstSeenGroupOrderWhenLaterBatchCompletesFirst() {
        OutOfOrderExecutor executor = new OutOfOrderExecutor();
        ParallelAggregateOperator operator = operator(
                List.of(row("department", "Z", "amount", "1"),
                        row("department", "A", "amount", "2")),
                List.of("department"), List.of(spec(AggregateFunction.SUM, "amount", "total")),
                1, 2, executor);

        assertEquals(List.of(
                        row("department", "Z", "total", "1"),
                        row("department", "A", "total", "2")),
                consume(operator));
        executor.shutdownNow();
    }

    @Test
    void handlesEmptyGlobalAndGroupedInputs() {
        List<AggregateSpecification> specs = List.of(
                AggregateSpecification.countAll("count"),
                spec(AggregateFunction.SUM, "amount", "total"));

        assertEquals(List.of(row("count", "0", "total", null)),
                parallel(List.of(), List.of(), specs, 3, 2));
        assertEquals(List.of(),
                parallel(List.of(), List.of("department"), specs, 3, 2));
    }

    @Test
    void preservesNullGroupingAndAggregateValuesAcrossBatches() {
        List<Row> input = List.of(
                rowWithNullableValues("department", null, "amount", null),
                row("department", "Sales", "amount", "1"),
                rowWithNullableValues("department", null, "amount", "3"));
        List<AggregateSpecification> specs = List.of(
                AggregateSpecification.countAll("rows"),
                spec(AggregateFunction.COUNT, "amount", "non_null"),
                spec(AggregateFunction.AVG, "amount", "average"));

        assertEquals(List.of(
                        rowWithNullableValues("department", null,
                                "rows", "2", "non_null", "1", "average", "3.0000"),
                        row("department", "Sales", "rows", "1", "non_null", "1",
                                "average", "1.0000")),
                parallel(input, List.of("department"), specs, 1, 2));
    }

    @Test
    void mergesDecimalSumAndAverageExactlyAcrossBatchBoundaries() {
        List<Row> input = List.of(
                row("amount", "0.10"),
                row("amount", "0.20"),
                row("amount", "2.4689"));
        List<Row> results = parallel(input, List.of(), List.of(
                spec(AggregateFunction.SUM, "amount", "sum"),
                spec(AggregateFunction.AVG, "amount", "average")), 1, 2);

        assertEquals(List.of(row("sum", "2.7689", "average", "0.9230")), results);
    }

    @Test
    void supportsMoreBatchesThanTheInFlightLimit() {
        TrackingExecutor executor = new TrackingExecutor(2);
        List<Row> input = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            input.add(row("department", "D" + (index % 3), "amount", "1"));
        }
        ParallelAggregateOperator operator = operator(input, List.of("department"),
                List.of(AggregateSpecification.countAll("count")), 1, 2, executor);

        List<Row> result = consume(operator);

        assertEquals(3, result.size());
        assertTrue(executor.maximumOutstanding() <= 2);
        executor.shutdownNow();
    }

    @Test
    void childIsAccessedOnlyByTheCallingThreadAndClosedOnce() {
        Thread callingThread = Thread.currentThread();
        ThreadCheckingOperator child = new ThreadCheckingOperator(
                List.of(row("amount", "1"), row("amount", "2")));
        ParallelAggregateOperator operator = new ParallelAggregateOperator(
                child, List.of(), List.of(spec(AggregateFunction.SUM, "amount", "total")),
                1, 2);

        assertEquals(List.of(row("total", "3")), consume(operator));
        assertEquals(callingThread, child.accessThread);
        assertEquals(1, child.closeCount.get());
    }

    @Test
    void propagatesWorkerFailuresAndClosesChild() {
        TrackingOperator child = new TrackingOperator(List.of(row("amount", "bad")));
        ParallelAggregateOperator operator = new ParallelAggregateOperator(
                child, List.of(), List.of(spec(AggregateFunction.SUM, "amount", "total")),
                1, 2);

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, operator::hasNext);

        assertTrue(exception.getMessage().contains("bad"));
        assertEquals(1, child.closeCount.get());
    }

    @Test
    void respectsOwnedAndExternallySuppliedExecutorLifecycle() {
        TrackingExecutor owned = new TrackingExecutor(1);
        TrackingOperator ownedChild = new TrackingOperator(List.of());
        ParallelAggregateOperator ownedOperator = new ParallelAggregateOperator(
                ownedChild, List.of(), List.of(AggregateSpecification.countAll("count")),
                2, 1, owned, true);
        ownedOperator.close();
        assertTrue(owned.isShutdown());
        assertEquals(1, ownedChild.closeCount.get());

        TrackingExecutor ownedAfterExecution = new TrackingExecutor(1);
        ParallelAggregateOperator completedOwnedOperator = new ParallelAggregateOperator(
                new TrackingOperator(List.of(row("amount", "1"))), List.of(),
                List.of(AggregateSpecification.countAll("count")), 2, 1,
                ownedAfterExecution, true);
        assertEquals(List.of(row("count", "1")), consume(completedOwnedOperator));
        assertTrue(ownedAfterExecution.isShutdown());

        TrackingExecutor external = new TrackingExecutor(1);
        TrackingOperator externalChild = new TrackingOperator(List.of());
        ParallelAggregateOperator externalOperator = new ParallelAggregateOperator(
                externalChild, List.of(), List.of(AggregateSpecification.countAll("count")),
                2, 1, external);
        assertEquals(List.of(row("count", "0")), consume(externalOperator));
        assertFalse(external.isShutdown());
        assertEquals(1, externalChild.closeCount.get());
        external.shutdownNow();
    }

    @Test
    void nextAfterExhaustionThrowsNoSuchElementExceptionAndCloseIsIdempotent() {
        ParallelAggregateOperator operator = operator(List.of(), List.of("department"),
                List.of(AggregateSpecification.countAll("count")), 2, 1, null);

        assertFalse(operator.hasNext());
        assertThrows(NoSuchElementException.class, operator::next);
        operator.close();
        operator.close();
    }

    private List<Row> parallel(
            List<Row> input,
            List<String> groups,
            List<AggregateSpecification> specifications,
            int batchSize,
            int maxInFlight) {
        return consume(operator(input, groups, specifications, batchSize, maxInFlight, null));
    }

    private ParallelAggregateOperator operator(
            List<Row> input,
            List<String> groups,
            List<AggregateSpecification> specifications,
            int batchSize,
            int maxInFlight,
            ExecutorService executor) {
        TrackingOperator child = new TrackingOperator(input);
        return executor == null
                ? new ParallelAggregateOperator(child, groups, specifications, batchSize, maxInFlight)
                : new ParallelAggregateOperator(child, groups, specifications,
                batchSize, maxInFlight, executor);
    }

    private List<Row> sequential(
            List<Row> input, List<String> groups, List<AggregateSpecification> specifications) {
        return consume(new AggregateOperator(new TrackingOperator(input), groups, specifications));
    }

    private List<Row> consume(Operator operator) {
        List<Row> rows = new ArrayList<>();
        try (operator) {
            while (operator.hasNext()) {
                rows.add(operator.next());
            }
        }
        return rows;
    }

    private AggregateSpecification spec(AggregateFunction function, String input, String output) {
        return new AggregateSpecification(function, input, output);
    }

    private Row row(String key, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, value);
        return new Row(values);
    }

    private Row row(String key1, String value1, String key2, String value2) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        return new Row(values);
    }

    private Row row(String key1, String value1, String key2, String value2,
                    String key3, String value3) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        values.put(key3, value3);
        return new Row(values);
    }

    private Row row(String key1, String value1, String key2, String value2,
                    String key3, String value3, String key4, String value4) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        values.put(key3, value3);
        values.put(key4, value4);
        return new Row(values);
    }

    private Row rowWithNullableValues(String... entries) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put(entries[index], entries[index + 1]);
        }
        return new Row(values);
    }

    private static class TrackingOperator implements Operator {
        private final List<Row> rows;
        final AtomicInteger closeCount = new AtomicInteger();
        private int index;

        private TrackingOperator(List<Row> rows) {
            this.rows = rows;
        }

        @Override
        public boolean hasNext() {
            return index < rows.size();
        }

        @Override
        public Row next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return rows.get(index++);
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static final class ThreadCheckingOperator extends TrackingOperator {
        private Thread accessThread;

        private ThreadCheckingOperator(List<Row> rows) {
            super(rows);
        }

        @Override
        public boolean hasNext() {
            assertCallerThread();
            return super.hasNext();
        }

        @Override
        public Row next() {
            assertCallerThread();
            return super.next();
        }

        @Override
        public void close() {
            assertCallerThread();
            super.close();
        }

        private void assertCallerThread() {
            if (accessThread == null) {
                accessThread = Thread.currentThread();
            }
            if (accessThread != Thread.currentThread()) {
                throw new AssertionError("Child operator accessed from a worker thread");
            }
        }
    }

    private static class TrackingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final AtomicInteger outstanding = new AtomicInteger();
        private final AtomicInteger maximumOutstanding = new AtomicInteger();

        private TrackingExecutor(int threads) {
            delegate = Executors.newFixedThreadPool(threads);
        }

        @Override
        public void execute(Runnable command) {
            int active = outstanding.incrementAndGet();
            maximumOutstanding.accumulateAndGet(active, Math::max);
            delegate.execute(() -> {
                try {
                    command.run();
                } finally {
                    outstanding.decrementAndGet();
                }
            });
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        private int maximumOutstanding() {
            return maximumOutstanding.get();
        }
    }

    private static final class OutOfOrderExecutor extends TrackingExecutor {
        private final AtomicInteger taskNumber = new AtomicInteger();
        private final CountDownLatch secondCompleted = new CountDownLatch(1);

        private OutOfOrderExecutor() {
            super(2);
        }

        @Override
        public void execute(Runnable command) {
            int task = taskNumber.incrementAndGet();
            super.execute(() -> {
                if (task == 1) {
                    try {
                        if (!secondCompleted.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Second batch did not complete");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    command.run();
                } else {
                    command.run();
                    secondCompleted.countDown();
                }
            });
        }
    }
}
