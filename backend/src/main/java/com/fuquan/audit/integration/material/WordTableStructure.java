package com.fuquan.audit.integration.material;

import com.fuquan.audit.common.material.application.port.MaterialParsePort;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/** Native Word row/cell extraction shared by Office intake and the contract compatibility adapter. */
public final class WordTableStructure {
    private WordTableStructure() {}

    public static MaterialParsePort.Table parse(XWPFTable table, String locator) {
        var cells = new ArrayList<MaterialParsePort.Cell>();
        int rowNumber = 0;
        for (var row : table.getRows()) {
            rowNumber++;
            int column = 0;
            for (var cell : row.getTableCells()) {
                column++;
                if (cells.size() >= 100000) throw new IllegalArgumentException("WORD_TABLE_CELL_LIMIT");
                cells.add(new MaterialParsePort.Cell(locator + "/row:" + rowNumber + "/cell:" + column,
                        rowNumber, column, cell.getText(), "TEXT", null));
            }
        }
        return new MaterialParsePort.Table(locator, cells, false);
    }

    /** Canonical contract rows keep embedded cell newlines separate from physical row delimiters. */
    public static String canonicalRow(List<MaterialParsePort.Cell> cells) {
        return cells.stream().map(cell -> cell.value().replace("\r\n", "\u2028")
                .replace('\r', '\u2028').replace('\n', '\u2028'))
                .collect(java.util.stream.Collectors.joining("\t"));
    }
}
