package com.fuquan.audit.contract.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 严格解析规范财务中文大写人民币金额。
 *
 * <p>解析器只接受具有唯一“元”分隔、合法大小单位顺序和规范角分表达的文本；无法确认语义时返回空值，
 * 由 F02 金额大小写规则生成 NOT_EVALUATED，而不是猜测金额。</p>
 */
public final class ChineseRmbUppercaseParser {

    private static final Map<Character, Integer> DIGITS = Map.of(
            '零', 0, '壹', 1, '贰', 2, '叁', 3, '肆', 4,
            '伍', 5, '陆', 6, '柒', 7, '捌', 8, '玖', 9);
    private static final Map<Character, Integer> SMALL_UNITS = Map.of(
            '拾', 10, '佰', 100, '仟', 1000);
    private static final Pattern FRACTION = Pattern.compile(
            "(?:([零壹贰叁肆伍陆柒捌玖])角)?(?:零)?(?:([零壹贰叁肆伍陆柒捌玖])分)?");

    /**
     * 将规范中文大写人民币文本解析为金额值。
     *
     * @param source 合同中的金额大写文本，可为空
     * @return 成功解析的金额；文本缺失或不符合规范时返回空
     */
    public Optional<Money> parse(String source) {
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        String value = source.replaceAll("\\s+", "")
                .replaceFirst("^人民币", "")
                .replace('圆', '元')
                .replace('正', '整');
        int yuanIndex = value.indexOf('元');
        if (yuanIndex < 0 || yuanIndex != value.lastIndexOf('元')) {
            return Optional.empty();
        }
        Optional<BigInteger> integer = parseInteger(value.substring(0, yuanIndex));
        if (integer.isEmpty()) {
            return Optional.empty();
        }
        String fractionText = value.substring(yuanIndex + 1);
        BigDecimal fraction;
        if ("整".equals(fractionText)) {
            fraction = BigDecimal.ZERO;
        } else {
            Matcher matcher = FRACTION.matcher(fractionText);
            if (!matcher.matches() || (matcher.group(1) == null && matcher.group(2) == null)) {
                return Optional.empty();
            }
            int jiao = matcher.group(1) == null ? 0 : DIGITS.get(matcher.group(1).charAt(0));
            int fen = matcher.group(2) == null ? 0 : DIGITS.get(matcher.group(2).charAt(0));
            fraction = new BigDecimal(jiao).movePointLeft(1)
                    .add(new BigDecimal(fen).movePointLeft(2));
        }
        return Optional.of(Money.of(new BigDecimal(integer.get()).add(fraction).setScale(2)));
    }

    /**
     * 解析“元”之前的中文整数部分，并严格校验大小单位顺序。
     *
     * @param text 不含币种、元和角分的中文整数文本
     * @return 解析后的非负整数；文本不合法时返回空
     */
    private Optional<BigInteger> parseInteger(String text) {
        if (text.isEmpty() || !text.matches("[零壹贰叁肆伍陆柒捌玖拾佰仟万亿]+")) {
            return Optional.empty();
        }
        BigInteger total = BigInteger.ZERO;
        BigInteger section = BigInteger.ZERO;
        Integer pendingDigit = null;
        int lastSmallUnit = 10_000;
        int lastLargeUnit = Integer.MAX_VALUE;
        boolean sawValue = false;

        for (int index = 0; index < text.length(); index++) {
            char symbol = text.charAt(index);
            Integer digit = DIGITS.get(symbol);
            if (digit != null) {
                if (pendingDigit != null && pendingDigit != 0) {
                    return Optional.empty();
                }
                pendingDigit = digit;
                sawValue = true;
                continue;
            }
            Integer smallUnit = SMALL_UNITS.get(symbol);
            if (smallUnit != null) {
                if (smallUnit >= lastSmallUnit) {
                    return Optional.empty();
                }
                int multiplier = pendingDigit == null ? (symbol == '拾' ? 1 : -1) : pendingDigit;
                if (multiplier <= 0) {
                    return Optional.empty();
                }
                section = section.add(BigInteger.valueOf((long) multiplier * smallUnit));
                pendingDigit = null;
                lastSmallUnit = smallUnit;
                sawValue = true;
                continue;
            }
            int largeUnit = symbol == '万' ? 10_000 : symbol == '亿' ? 100_000_000 : -1;
            if (largeUnit < 0 || largeUnit >= lastLargeUnit) {
                return Optional.empty();
            }
            if (pendingDigit != null) {
                section = section.add(BigInteger.valueOf(pendingDigit));
                pendingDigit = null;
            }
            if (section.signum() == 0) {
                return Optional.empty();
            }
            total = total.add(section.multiply(BigInteger.valueOf(largeUnit)));
            section = BigInteger.ZERO;
            lastSmallUnit = 10_000;
            lastLargeUnit = largeUnit;
        }
        if (pendingDigit != null) {
            section = section.add(BigInteger.valueOf(pendingDigit));
        }
        return sawValue ? Optional.of(total.add(section)) : Optional.empty();
    }
}
