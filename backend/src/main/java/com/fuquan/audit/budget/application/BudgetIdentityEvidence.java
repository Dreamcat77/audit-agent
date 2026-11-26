package com.fuquan.audit.budget.application;

import static com.fuquan.audit.budget.application.BudgetUploadedFacts.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Budget identity roles and comparison contexts explicitly documented in the batch. */
final class BudgetIdentityEvidence {
    private BudgetIdentityEvidence() {}

    static boolean subjectAsProject(Fact fact, String source) {
        if (!fact.field().equals("projectName")) return false;
        String value = Pattern.quote(fact.value());
        if (Pattern.compile("项目名称\\s*[:：\\t|]\\s*" + value + "(?=$|[\\s；;。|])").matcher(source).find()) return false;
        return Pattern.compile("(?:预算科目|科目名称|支出科目|支出项目)\\s*[:：\\t|]\\s*" + value + "(?=$|[\\s；;。|])").matcher(source).find();
    }

    static Map<String,String> departmentAliases(List<Input> inputs) {
        var candidates = new HashMap<String,Set<String>>();
        var departmentsByUnit = new HashMap<String,Set<String>>();
        for (var input : inputs) {
            if (!"PARSED".equals(input.status()) || input.snapshot() == null) continue;
            for (var row : input.snapshot().rows()) {
                var facts = row.facts().stream().filter(f -> f.field().equals("departmentName")
                    && !Set.of("REFERENCE", "GENERAL").contains(Objects.toString(f.subjectScope(), ""))).toList();
                for (var unit : facts) {
                    if (!labelled(unit, "申报单位|申请单位")) continue;
                    String full = key(unit.value());
                    for (var department : facts) {
                        String shortName = key(department.value());
                        // An applicant unit and its preparing department are different
                        // roles. They can identify one comparison context only when
                        // the batch explicitly documents a one-to-one relationship.
                        // Never infer it from a suffix or from unrelated paragraphs.
                        if (shortName.length() < 3 || full.equals(shortName)
                            || !labelled(department, "部门|部门名称")
                            || !physicalRow(unit.locator()).equals(physicalRow(department.locator()))) continue;
                        candidates.computeIfAbsent(shortName, ignored -> new HashSet<>()).add(full);
                        departmentsByUnit.computeIfAbsent(full, ignored -> new HashSet<>()).add(shortName);
                    }
                }
            }
        }
        var aliases = new HashMap<String,String>();
        candidates.forEach((name, units) -> {
            if (units.size() == 1) {
                String unit = units.iterator().next();
                if (departmentsByUnit.get(unit).size() == 1) aliases.put(name, unit);
            }
        });
        return Map.copyOf(aliases);
    }

    private static boolean labelled(Fact fact, String labels) {
        return Pattern.compile("(?:^|[\\s；;|])(?:" + labels + ")\\s*[:：\\t|]\\s*"
            + Pattern.quote(fact.value()) + "(?=$|[\\s；;。|])").matcher(Objects.toString(fact.excerpt(), "")).find();
    }

    private static String physicalRow(String locator) { return locator.replaceFirst("/col:[0-9]+$", ""); }
    private static String key(String text) { return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]+", "").toLowerCase(Locale.ROOT); }
}
