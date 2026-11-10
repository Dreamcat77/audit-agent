package com.fuquan.audit.contract.domain;

import java.text.Normalizer;

/** A party caption or unfilled form is not a contracting party's name. */
public final class ContractPartyName {
    private ContractPartyName() {}
    public static String canonical(String value) {
        if(value==null)return null;
        return Normalizer.normalize(value,Normalizer.Form.NFKC).strip()
                .replaceFirst("\\s*\\((?:以下简称|简称|以下称)[:：]?[“\"‘]?[^()]{1,30}[”\"’]?\\)\\s*$", "").strip()
                .replaceFirst("\\s*\\((?:主管|主管单位|盖章|签章|甲方|乙方|采购人|供应商)\\)\\s*$", "").strip();
    }
    public static boolean isPresent(String value) {
        if (value == null) return false;
        String name = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}]+", "").strip();
        if (name.isEmpty() || name.matches("(?i)(?:无|未填(?:写)?|待填(?:写)?|待定|未知|不详|暂无|null|unknown|n/a|x+|[_—–\\-…□○]+)")) return false;
        if(name.matches("(?:甲方|乙方|采购人|供应商)?(?:单位名称|公司名称|单位|名称|名称待填|名称待填写)"))return false;
        String role = "(?:甲方|乙方|丙方|定作方|定做方|承揽方|委托方|受托方|采购人|供应商|出租方|承租方|买方|卖方|发包方|承包方|盖章|签章|签字|签名|单位名称|公司名称|名称|Party[ABC])";
        String remainder=name.replaceAll("(?i)"+role+"|住所|地址|法定代表人|委托代理人|联系人|电话|传真|开户银行|账号|邮政编码", "")
                .replaceAll("[\\p{P}\\p{S}]+", "");
        if (remainder.isEmpty()) return false;
        return !name.matches("(?:住所|地址|法定代表人|委托代理人|联系人|电话|传真|开户银行|账号|邮政编码)[:：].*");
    }
}
