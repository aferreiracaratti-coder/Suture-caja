package com.suture.caja;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Adapted from Marcha POS BillingMath.allocateByWeights for retail sale discounts. */
public final class MoneyAllocation {
    private MoneyAllocation() {}

    public static List<BigDecimal> byWeights(BigDecimal total,List<BigDecimal> weights) {
        if (total==null || total.signum()<0 || total.scale()>2 || weights==null || weights.isEmpty()
            || weights.stream().anyMatch(w->w==null || w.signum()<0)) throw new IllegalArgumentException("Distribución monetaria inválida");
        BigDecimal sum=weights.stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        if (total.compareTo(sum)>0 || (sum.signum()==0 && total.signum()>0)) throw new IllegalArgumentException("Descuento superior a líneas");
        if (total.signum()==0) return weights.stream().map(ignored->BigDecimal.ZERO.setScale(2)).toList();
        long totalCents=total.movePointRight(2).longValueExact();
        List<Long> cents=new ArrayList<>();
        List<BigDecimal> remainders=new ArrayList<>();
        long allocated=0;
        for (BigDecimal weight:weights) {
            BigDecimal exact=BigDecimal.valueOf(totalCents).multiply(weight).divide(sum,16,RoundingMode.DOWN);
            long floor=exact.longValue();
            cents.add(floor);remainders.add(exact.subtract(BigDecimal.valueOf(floor)));allocated+=floor;
        }
        List<Integer> order=new ArrayList<>();
        for (int i=0;i<weights.size();i++) order.add(i);
        order.sort(Comparator.<Integer,BigDecimal>comparing(remainders::get).reversed().thenComparingInt(Integer::intValue));
        for (long left=totalCents-allocated,index=0;left>0;left--,index++) {
            int target=order.get((int)(index%order.size()));
            cents.set(target,cents.get(target)+1);
        }
        return cents.stream().map(value->BigDecimal.valueOf(value,2)).toList();
    }
}
