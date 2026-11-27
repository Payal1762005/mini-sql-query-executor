package io.github.payal1762005.minisql.operators;

/**
 * Controls whether planned aggregate queries use parallel aggregation.
 */
public record AggregationExecutionConfig(
        boolean parallelEnabled,
        int workerCount,
        int batchSize) {

    public AggregationExecutionConfig {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("Aggregation worker count must be greater than zero");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Aggregation batch size must be greater than zero");
        }
    }

    public static AggregationExecutionConfig serial() {
        return new AggregationExecutionConfig(false, 1, 1024);
    }

    public static AggregationExecutionConfig parallel(int workerCount, int batchSize) {
        return new AggregationExecutionConfig(true, workerCount, batchSize);
    }
}
