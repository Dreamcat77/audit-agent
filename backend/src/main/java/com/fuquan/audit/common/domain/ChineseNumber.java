package com.fuquan.audit.common.domain;

import java.math.BigDecimal;
import java.util.Optional;

/** Exact Chinese/Arabic nonnegative numbers used for counts and written rates. */
public final class ChineseNumber {
    private ChineseNumber() {}
    public static Optional<BigDecimal> parse(String text) {
        if (text == null || text.length() > 40) return Optional.empty();
        String value = text.strip().replace('点', '.');
        if (value.matches("[0-9]+(?:\\.[0-9]+)?")) return Optional.of(new BigDecimal(value));
        String from = "〇一二两三四五六七八九十百千万亿";
        String to =   "零壹贰贰叁肆伍陆柒捌玖拾佰仟万亿";
        StringBuilder converted = new StringBuilder();
        for (char c : value.toCharArray()) {
            int index = from.indexOf(c);
            converted.append(index < 0 ? c : to.charAt(index));
        }
        String[] parts = converted.toString().split("\\.", -1);
        if (parts.length==1 && parts[0].matches("[零壹贰叁肆伍陆柒捌玖]+")) {
            StringBuilder digits=new StringBuilder();
            for(char c:parts[0].toCharArray())digits.append("零壹贰叁肆伍陆柒捌玖".indexOf(c));
            return Optional.of(new BigDecimal(digits.toString()));
        }
        var integer = RmbAmountParser.parse(parts[0] + "元整");
        if (integer.isEmpty() || parts.length > 2) return Optional.empty();
        if (parts.length == 1) return integer;
        if (parts[1].isEmpty() || parts[1].length() > 8) return Optional.empty();
        StringBuilder fraction = new StringBuilder();
        for (char c : parts[1].toCharArray()) {
            int digit = "零壹贰叁肆伍陆柒捌玖".indexOf(c);
            if (digit < 0) return Optional.empty();
            fraction.append(digit);
        }
        return Optional.of(integer.get().add(new BigDecimal("0." + fraction)));
    }
}
