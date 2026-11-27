package io.github.payal1762005.minisql.operators;

import java.math.BigDecimal;

final class MinAggregateState extends AbstractNumericAggregateState {
    private BigDecimal minimum;

    MinAggregateState(String column) {
        super(column);
    }

    @Override
    protected void addValue(BigDecimal value) {
        if (minimum == null || value.compareTo(minimum) < 0) {
            minimum = value;
        }
    }

    @Override
    protected void mergeValues(AbstractNumericAggregateState other) {
        BigDecimal otherMinimum = ((MinAggregateState) other).minimum;
        if (otherMinimum != null && (minimum == null || otherMinimum.compareTo(minimum) < 0)) {
            minimum = otherMinimum;
        }
    }

    @Override
    public String result() {
        return minimum == null ? null : minimum.stripTrailingZeros().toPlainString();
    }
}
