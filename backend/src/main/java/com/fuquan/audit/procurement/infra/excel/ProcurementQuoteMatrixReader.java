package com.fuquan.audit.procurement.infra.excel;

import com.fuquan.audit.procurement.application.port.ProcurementQuoteSpreadsheetPort.*;
import com.fuquan.audit.procurement.domain.ProcurementQuoteComparison.*;
import java.io.IOException;
import java.math.*;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCell;

/** F06 strict horizontal quotation reader. Money is read as decimal text, never evaluated by AI. */
final class ProcurementQuoteMatrixReader {
    private ProcurementQuoteMatrixReader() {}
    static int header(Sheet sheet) {
        DataFormatter f = new DataFormatter();
        for (int r = 0; r <= Math.min(30, sheet.getLastRowNum()); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            var values = new ArrayList<String>();
            for (int c = 0; c < row.getLastCellNum(); c++) values.add(text(row, c, f));
            if (values.contains("品目名称") && values.contains("规格参数") && values.contains("数量")
                    && values.stream().filter(v -> v.startsWith("供应商")).count() >= 3) return r;
        }
        return -1;
    }
    static ParsedSheet read(Sheet sheet, int h) throws IOException {
        if (sheet.getLastRowNum() > 5000) throw new IOException("报价行数超过上限");
        DataFormatter f = new DataFormatter(); Row head = sheet.getRow(h); Row sub = sheet.getRow(h + 1);
        if (head.getLastCellNum() > 64 || sub == null) throw new IOException("三方比价表头不完整");
        Map<String,Integer> columns = new LinkedHashMap<>(); Map<String,Integer> suppliers = new LinkedHashMap<>();
        for (int c = 0; c < head.getLastCellNum(); c++) {
            String value = text(head,c,f); if (!value.isBlank()) columns.put(value,c);
            if (value.startsWith("供应商")) {
                if (!text(sub,c,f).equals("单价") || !text(sub,c+1,f).equals("总价"))
                    throw new IOException("供应商报价必须同时包含单价和总价");
                String name = value.replaceFirst("^供应商[A-ZＡ-Ｚ0-9０-９]+[（(](.+)[）)]$", "$1").strip();
                if (name.length() > 200 || suppliers.put(name,c) != null) throw new IOException("供应商重复或名称过长");
            }
        }
        if (suppliers.size() < 3 || suppliers.size() > 20) throw new IOException("三方比价需要 3 至 20 家不同供应商");
        for (String required : List.of("品目编号","品目名称","规格参数","单位","数量"))
            if (!columns.containsKey(required)) throw new IOException("三方比价缺少列：" + required);
        Map<String,BigDecimal> totals = new LinkedHashMap<>(); suppliers.keySet().forEach(s -> totals.put(s,BigDecimal.ZERO));
        List<Item> items = new ArrayList<>(); Set<String> codes = new HashSet<>();
        Row declaredTotals = null;
        for (int r = h + 2; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r); if (row == null) continue;
            String code = text(row, columns.get("品目编号"), f);
            if (text(row,0,f).equals("合计")) { if (declaredTotals != null) throw new IOException("存在多个合计行"); declaredTotals = row; continue; }
            if (code.isBlank()) {
                boolean hasQuote = suppliers.values().stream().anyMatch(c -> !text(row,c+1,f).isBlank());
                if (hasQuote && !text(row,0,f).equals("预算对比")) throw new IOException("第"+(r+1)+"行缺少品目编号");
                continue;
            }
            if (declaredTotals != null || !codes.add(code)) throw new IOException("品目重复或合计后出现报价行");
            String name = text(row,columns.get("品目名称"),f), spec = text(row,columns.get("规格参数"),f), unit = text(row,columns.get("单位"),f);
            if (name.isBlank() || spec.isBlank() || unit.isBlank() || code.length()>200 || name.length()>200 || spec.length()>2000)
                throw new IOException("第"+(r+1)+"行品目、规格或单位缺失/过长");
            BigDecimal quantity = decimal(row,columns.get("数量"));
            if (quantity.signum() <= 0 || quantity.scale() > 4) throw new IOException("数量必须为有效正数");
            List<Offer> offers = new ArrayList<>();
            for (var supplier : suppliers.entrySet()) {
                BigDecimal price = money(decimal(row,supplier.getValue()));
                BigDecimal total = money(decimal(row,supplier.getValue()+1,true));
                if (price.signum() <= 0 || price.multiply(quantity).setScale(2,RoundingMode.HALF_UP).compareTo(total) != 0)
                    throw new IOException("第"+(r+1)+"行 "+supplier.getKey()+" 单价×数量与总价不一致");
                totals.compute(supplier.getKey(), (key,old) -> old.add(total));
                offers.add(new Offer(supplier.getKey(),price,total));
            }
            BigDecimal min = offers.stream().map(Offer::totalPrice).min(BigDecimal::compareTo).orElseThrow();
            items.add(new Item(r+1,code,name,spec,unit,quantity,List.copyOf(offers),min,
                    offers.stream().filter(o->o.totalPrice().compareTo(min)==0).map(Offer::supplierName).toList()));
            if (items.size() > 1000) throw new IOException("品目数超过上限");
        }
        if (items.isEmpty()) throw new IOException("没有有效品目报价");
        if (declaredTotals != null) for (var s:suppliers.entrySet())
            if (money(decimal(declaredTotals,s.getValue()+1,true)).compareTo(totals.get(s.getKey())) != 0)
                throw new IOException(s.getKey()+" 合计与逐项报价总额不一致");
        List<String> conditions = new ArrayList<>(); BigDecimal budget = null;
        Pattern budgetPattern = Pattern.compile("预算上限\\s*[:：]?\\s*[¥￥]?\\s*([0-9,，]+(?:\\.[0-9]{1,2})?)(?![0-9.])");
        for (Row row:sheet) for (Cell cell:row) {
            String value = f.formatCellValue(cell).strip();
            var matcher = budgetPattern.matcher(value);
            while (matcher.find()) {
                BigDecimal found = money(new BigDecimal(matcher.group(1).replace(",", "").replace("，", "")));
                if (budget != null && found.compareTo(budget) != 0) throw new IOException("材料预算上限冲突");
                budget = found;
            }
            if (value.contains("所有报价") && value.contains("含税") && !conditions.contains(value)) conditions.add(value);
        }
        List<QuoteRow> rows = new ArrayList<>(); int index=1;
        for (var entry:totals.entrySet()) rows.add(new QuoteRow(index++,entry.getKey(),money(entry.getValue()),"同一采购清单，共"+items.size()+"个品目"));
        return new ParsedSheet(budget,rows,items,conditions);
    }
    private static String text(Row row,int c,DataFormatter f) { return row == null || row.getCell(c)==null ? "" : f.formatCellValue(row.getCell(c)).strip(); }
    private static BigDecimal decimal(Row row,int c) throws IOException {
        return decimal(row,c,false);
    }
    private static BigDecimal decimal(Row row,int c,boolean checkedTotal) throws IOException {
        Cell cell = row.getCell(c); if(cell==null) throw new IOException("报价或数量不能为空");
        // Cached formulas are accepted only for totals independently recomputed from literal factors.
        if (cell.getCellType()==CellType.FORMULA && !checkedTotal) throw new IOException("单价和数量请保存为原始数值，不能使用公式缓存作为报价依据");
        String value;
        if ((cell.getCellType()==CellType.NUMERIC || cell.getCellType()==CellType.FORMULA) && cell instanceof XSSFCell x) value=x.getRawValue();
        else if(cell.getCellType()==CellType.STRING) value=cell.getStringCellValue();
        else if(cell.getCellType()==CellType.NUMERIC) value=new DataFormatter(Locale.ROOT).formatCellValue(cell);
        else throw new IOException("报价或数量必须为十进制数，公式需要已保存的数值结果");
        try {
            if (value == null || value.length()>40 || !value.replace(",", "").strip().matches("[0-9]+(?:\\.[0-9]+)?")) throw new NumberFormatException();
            return new BigDecimal(value.replace(",", "").strip());
        } catch(NumberFormatException ex) { throw new IOException("报价或数量格式不正确",ex); }
    }
    private static BigDecimal money(BigDecimal amount) throws IOException {
        try {
            if(amount.signum()<0 || amount.compareTo(new BigDecimal("9999999999999999.99"))>0) throw new ArithmeticException();
            return amount.setScale(2,RoundingMode.UNNECESSARY);
        } catch(ArithmeticException ex) { throw new IOException("报价金额超出范围或超过两位小数",ex); }
    }
}
