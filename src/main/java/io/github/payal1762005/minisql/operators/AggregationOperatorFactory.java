package io.github.payal1762005.minisql.operators;

import java.util.Objects;

/**
 * Public construction boundary for configured aggregate operators.
 */
public final class AggregationOperatorFactory {
    private AggregationOperatorFactory() {
    }

    public static Operator parallel(
            Operator child,
            java.util.List<String> groupByColumns,
            java.util.List<AggregateSpecification> specifications,
            AggregationExecutionConfig config) {
        Objects.requireNonNull(config, "Aggregation execution configuration must not be null");
        if (!config.parallelEnabled()) {
            throw new IllegalArgumentException("Parallel aggregation is disabled");
        }
        return new ParallelAggregateOperator(child, groupByColumns, specifications,
                config.batchSize(), config.workerCount());
    }
}
