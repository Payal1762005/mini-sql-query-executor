package io.github.payal1762005.minisql.operators;

import java.math.BigDecimal;

final class MaxAggregateState extends AbstractNumericAggregateState {
    private BigDecimal maximum;

    MaxAggregateState(String column) {
        super(column);
    }

    @Override
    protected void addValue(BigDecimal value) {
        if (maximum == null || value.compareTo(maximum) > 0) {
            maximum = value;
        }
    }

    @Override
    protected void mergeValues(AbstractNumericAggregateState other) {
        BigDecimal otherMaximum = ((MaxAggregateState) other).maximum;
        if (otherMaximum != null && (maximum == null || otherMaximum.compareTo(maximum) > 0)) {
            maximum = otherMaximum;
        }
    }

    @Override
    public String result() {
        return maximum == null ? null : maximum.stripTrailingZeros().toPlainString();
    }
}
