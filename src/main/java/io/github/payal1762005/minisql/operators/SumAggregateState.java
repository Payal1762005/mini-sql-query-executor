package io.github.payal1762005.minisql.operators;

import java.math.BigDecimal;

final class SumAggregateState extends AbstractNumericAggregateState {
    private BigDecimal total = BigDecimal.ZERO;

    SumAggregateState(String column) {
        super(column);
    }

    @Override
    protected void addValue(BigDecimal value) {
        total = total.add(value);
    }

    @Override
    protected void mergeValues(AbstractNumericAggregateState other) {
        total = total.add(((SumAggregateState) other).total);
    }

    @Override
    public String result() {
        return isEmpty() ? null : total.stripTrailingZeros().toPlainString();
    }
}
