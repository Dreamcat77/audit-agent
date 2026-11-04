package com.fuquan.audit.contract.domain;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;

/** Shared exact operand kernel for formal F02 and independent analysis. No fake Contract required. */
public final class ContractArithmetic {
    private ContractArithmetic() {}
    public static boolean sameAmount(BigDecimal actual,BigDecimal expected){return Money.of(actual).sameValueAs(Money.of(expected));}
    public static BigDecimal lineAmount(BigDecimal quantity,BigDecimal price){return quantity.multiply(price);}
    public static BigDecimal sum(Collection<BigDecimal> amounts){return amounts.stream().reduce(BigDecimal.ZERO,BigDecimal::add);}
    public static String supplierName(String value){return value==null||value.isBlank()?null:Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
}
