package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.application.BudgetMaterialLine;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * F01 预算材料解析出站 Port。
 *
 * <p>Application 只依赖预算明细语义，不感知 Excel、PDF 或具体解析库。</p>
 */
public interface BudgetMaterialParser {

    boolean supports(String fileName, String contentType);

    List<BudgetMaterialLine> parse(InputStream inputStream, String fileName) throws IOException;
}
