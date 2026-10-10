package com.suture.caja;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoneyAllocationTest {
    @Test void distributesCentsWithoutLosingOrExceedingLineAmount() {
        List<BigDecimal> weights=List.of(new BigDecimal("0.01"),new BigDecimal("0.01"),new BigDecimal("0.01"),new BigDecimal("0.01"),new BigDecimal("0.01"));
        List<BigDecimal> result=MoneyAllocation.byWeights(new BigDecimal("0.03"),weights);
        assertThat(result.stream().reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("0.03");
        for (int i=0;i<result.size();i++) assertThat(result.get(i)).isBetween(BigDecimal.ZERO,weights.get(i));
        assertThat(result).containsExactly(new BigDecimal("0.01"),new BigDecimal("0.01"),new BigDecimal("0.01"),new BigDecimal("0.00"),new BigDecimal("0.00"));
    }
    @Test void zeroValueLineReceivesNoDiscount() {
        assertThat(MoneyAllocation.byWeights(new BigDecimal("0.01"),List.of(BigDecimal.ZERO,new BigDecimal("0.02"))))
            .containsExactly(new BigDecimal("0.00"),new BigDecimal("0.01"));
    }
}
