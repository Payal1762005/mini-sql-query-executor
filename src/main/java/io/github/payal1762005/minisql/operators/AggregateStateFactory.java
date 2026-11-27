package io.github.payal1762005.minisql.operators;

import java.util.Objects;

final class AggregateStateFactory {
    private AggregateStateFactory() {
    }

    static AggregateState create(AggregateSpecification specification) {
        Objects.requireNonNull(specification, "Aggregate specification must not be null");
        return switch (specification.function()) {
            case COUNT -> "*".equals(specification.inputColumn())
                    ? new CountStarAggregateState()
                    : new CountColumnAggregateState(specification.inputColumn());
            case SUM -> new SumAggregateState(specification.inputColumn());
            case AVG -> new AvgAggregateState(specification.inputColumn());
            case MIN -> new MinAggregateState(specification.inputColumn());
            case MAX -> new MaxAggregateState(specification.inputColumn());
        };
    }
}
