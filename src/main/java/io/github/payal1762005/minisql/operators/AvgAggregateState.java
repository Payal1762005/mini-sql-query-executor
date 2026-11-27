package io.github.payal1762005.minisql.operators;

import java.math.BigDecimal;

final class AvgAggregateState extends AbstractNumericAggregateState {
    private BigDecimal total = BigDecimal.ZERO;

    AvgAggregateState(String column) {
        super(column);
    }

    @Override
    protected void addValue(BigDecimal value) {
        total = total.add(value);
    }

    @Override
    protected void mergeValues(AbstractNumericAggregateState other) {
        total = total.add(((AvgAggregateState) other).total);
    }

    @Override
    public String result() {
        return isEmpty() ? null
                : total.divide(BigDecimal.valueOf(count),
                AggregateOperator.AVG_SCALE, AggregateOperator.AVG_ROUNDING_MODE).toPlainString();
    }
}
