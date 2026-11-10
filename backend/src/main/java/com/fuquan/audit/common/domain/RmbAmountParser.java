package com.fuquan.audit.common.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/** Source amounts only. No rounding, guessed units, or AI arithmetic. */
public final class RmbAmountParser {
    private RmbAmountParser() {}
    private static final String DIGITS="零壹贰叁肆伍陆柒捌玖";
    private static final Pattern ARABIC=Pattern.compile("\\+?((?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?)(?:(亿元?|万元?|千元?|元)整?)?");
    private static final Pattern DUAL=Pattern.compile("(.+?)[(]?(?:人民币)?大写[:：](.+?)[)]?");
    private static final Pattern PAREN_DUAL=Pattern.compile("([^()]+)\\(([^()]+)\\)");
    private static final Pattern FRACTION=Pattern.compile("(?:([零壹贰叁肆伍陆柒捌玖])角)?零?(?:([零壹贰叁肆伍陆柒捌玖])分)?");

    public static Optional<BigDecimal> parse(String source) {
        if(source==null||source.length()>300)return Optional.empty();
        String value=Normalizer.normalize(source,Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]","")
            .replace('，',',').replace('貳','贰').replace('參','叁').replace('叄','叁').replace('陸','陆')
            .replace('萬','万').replace('億','亿').replace('圓','元').replace('圆','元').replace('正','整');
        // A sentence terminator after an explicit currency/fraction unit is not
        // part of the amount. Never strip arbitrary suffixes or a decimal point.
        value=value.replaceFirst("(?<=[元角分整币BYby])[。；;]$", "");
        // A source amount may carry a tax-basis annotation. This does not change
        // its value; only these exact annotations are removable, never an
        // alternative amount, percentage, currency, or arbitrary parenthesis.
        value=value.replaceFirst("\\((?:含税|不含税|税前|税后)\\)[。；;]?$", "");
        var dual=DUAL.matcher(value);
        if(dual.matches()) {
            var a=single(dual.group(1));var b=single(dual.group(2));
            return a.isPresent()&&b.isPresent()&&a.get().compareTo(b.get())==0?a:Optional.empty();
        }
        var parenthetical=PAREN_DUAL.matcher(value.replaceFirst("(?<=\\))[。；;]$", ""));
        if(parenthetical.matches()) {
            String first=parenthetical.group(1),second=parenthetical.group(2);
            // Only corroborating financial-Chinese/Arabic monetary notation.
            // A parenthesized alternative or disagreement is never resolved by guessing.
            boolean firstFinancial=first.matches(".*[壹贰叁肆伍陆柒捌玖拾佰仟].*");
            boolean secondFinancial=second.matches(".*[壹贰叁肆伍陆柒捌玖拾佰仟].*");
            if(firstFinancial==secondFinancial)return Optional.empty();
            var a=single(first);var b=single(second);
            return a.isPresent()&&b.isPresent()&&a.get().compareTo(b.get())==0?a:Optional.empty();
        }
        return single(value);
    }
    private static Optional<BigDecimal> single(String value) {
        value=value.replaceFirst("(?:人民币|(?i:RMB|CNY))$", "");
        value=value.replaceFirst("^(?:大写[:：]?)?(?:人民币|(?i:RMB|CNY)|[¥￥])?","");
        if(value.matches("[零〇一二两三四五六七八九十百千万亿元角分整]+")) {
            String from="〇一二两三四五六七八九十百千",to="零壹贰贰叁肆伍陆柒捌玖拾佰仟";
            var converted=new StringBuilder();
            for(char c:value.toCharArray()){int index=from.indexOf(c);converted.append(index<0?c:to.charAt(index));}
            value=converted.toString();
        }
        try {
            var arabic=ARABIC.matcher(value);
            BigDecimal amount;
            if(arabic.matches()) {
                BigDecimal unit=switch(arabic.group(2)==null?"":arabic.group(2)) {
                    case "亿", "亿元" -> new BigDecimal("100000000");
                    case "万", "万元" -> new BigDecimal("10000");
                    case "千", "千元" -> new BigDecimal("1000");
                    default -> BigDecimal.ONE;
                };
                amount=new BigDecimal(arabic.group(1).replace(",","")).multiply(unit);
            } else {
                // A financial integer in a labelled amount cell may omit 元/整.
                if(value.matches("[零壹贰叁肆伍陆柒捌玖拾佰仟万亿]+"))value+="元整";
                int yuan=value.indexOf('元');
                if(yuan<1||yuan!=value.lastIndexOf('元'))return Optional.empty();
                var integer=integer(value.substring(0,yuan));
                if(integer.isEmpty())return Optional.empty();
                String fraction=value.substring(yuan+1);
                amount=new BigDecimal(integer.get());
                if(fraction.endsWith("整")||fraction.endsWith("正"))fraction=fraction.substring(0,fraction.length()-1);
                if(!fraction.isEmpty()) {
                    var m=FRACTION.matcher(fraction);
                    if(!m.matches()||(m.group(1)==null&&m.group(2)==null))return Optional.empty();
                    int jiao=m.group(1)==null?0:DIGITS.indexOf(m.group(1));
                    int fen=m.group(2)==null?0:DIGITS.indexOf(m.group(2));
                    amount=amount.add(BigDecimal.valueOf(jiao,1)).add(BigDecimal.valueOf(fen,2));
                }
            }
            amount=amount.setScale(2,RoundingMode.UNNECESSARY);
            return amount.signum()>=0&&amount.precision()<=18?Optional.of(amount):Optional.empty();
        } catch(ArithmeticException|NumberFormatException error) {return Optional.empty();}
    }
    private static Optional<BigInteger> integer(String text) {
        if(!text.matches("[零壹贰叁肆伍陆柒捌玖拾佰仟万亿]+"))return Optional.empty();
        BigInteger total=BigInteger.ZERO,section=BigInteger.ZERO;
        Integer digit=null;int lastSmall=10000,lastLarge=Integer.MAX_VALUE;
        for(char c:text.toCharArray()) {
            int d=DIGITS.indexOf(c);
            if(d>=0) {if(digit!=null&&digit!=0)return Optional.empty();digit=d;continue;}
            int small=switch(c) {case '拾'->10;case '佰'->100;case '仟'->1000;default->0;};
            if(small>0) {
                int multiplier=digit==null?(small==10?1:-1):digit;
                if(small>=lastSmall||multiplier<=0)return Optional.empty();
                section=section.add(BigInteger.valueOf((long)multiplier*small));digit=null;lastSmall=small;
            } else {
                int large=c=='万'?10000:100000000;
                if(large>=lastLarge)return Optional.empty();
                if(digit!=null)section=section.add(BigInteger.valueOf(digit));
                if(section.signum()==0)return Optional.empty();
                total=total.add(section.multiply(BigInteger.valueOf(large)));
                section=BigInteger.ZERO;digit=null;lastSmall=10000;lastLarge=large;
            }
        }
        if(digit!=null)section=section.add(BigInteger.valueOf(digit));
        var result=total.add(section);
        // Reject ambiguous shorthand such as 壹佰贰 (102 or 120), repeated units,
        // and misplaced zeros instead of silently assigning a plausible value.
        String canonical=render(result);
        return canonical.equals(text)||canonical.startsWith("壹拾")&&canonical.substring(1).equals(text)
            ?Optional.of(result):Optional.empty();
    }
    private static String render(BigInteger value) {
        if(value.signum()==0)return "零";
        String[] large={"","万","亿","万亿"};
        var groups=new java.util.ArrayList<Integer>();
        var base=BigInteger.valueOf(10000);
        while(value.signum()>0) {var div=value.divideAndRemainder(base);groups.add(div[1].intValueExact());value=div[0];}
        if(groups.size()>4)return "";
        var out=new StringBuilder();boolean zero=false;
        for(int i=groups.size()-1;i>=0;i--) {
            int group=groups.get(i);
            if(group==0) {if(!out.isEmpty())zero=true;continue;}
            if(!out.isEmpty()&&(zero||group<1000))out.append('零');
            String[] units={"仟","佰","拾",""};boolean gap=false;
            for(int pos=0,divisor=1000;pos<4;pos++,divisor/=10) {
                int d=group/divisor%10;
                if(d==0) {if(group>=divisor*10)gap=true;continue;}
                if(gap)out.append('零');gap=false;
                out.append(DIGITS.charAt(d)).append(units[pos]);
            }
            out.append(large[i]);zero=false;
        }
        return out.toString();
    }
}
