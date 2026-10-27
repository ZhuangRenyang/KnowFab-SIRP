package com.factory.hub.report;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 把报表整成 xlsx。
 * <p>
 * 为什么不用 CSV：厂里交报表是要打印和贴到月度总结里的，
 * CSV 到客户手上双击打开是乱码（Excel 按 GBK 解 UTF-8），
 * 一个「导出后打不开」的印象能把前面所有工作抵消掉。
 * <p>
 * 表头冻结 + 列宽固定 + 数字右对齐，是为了让车间主任直接打印，
 * 不用再花半小时调格式。
 */
public final class ProdExcel {

    private ProdExcel() {
    }

    @SuppressWarnings("unchecked")
    public static byte[] build(Map<String, Object> daily) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            String date = String.valueOf(daily.get("date"));
            String planName = String.valueOf(daily.getOrDefault("planName", ""));

            sheetShifts(wb, st, daily, date, planName);
            sheetDevices(wb, st, daily, date);
            sheetStops(wb, st, daily, date);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void sheetShifts(Workbook wb, Styles st, Map<String, Object> daily,
                                    String date, String planName) {
        Sheet sh = wb.createSheet("班次汇总");
        int r = 0;
        r = title(sh, st, r, "生产报表 " + date + "（" + planName + "）", 7);

        Row head = sh.createRow(r++);
        String[] cols = {"班次", "时间", "产量(件)", "运行(h)", "待机(h)", "停机(h)", "稼动率"};
        for (int i = 0; i < cols.length; i++) {
            head.createCell(i).setCellValue(cols[i]);
            head.getCell(i).setCellStyle(st.head);
        }

        List<Map<String, Object>> shifts = (List<Map<String, Object>>) daily.get("shifts");
        if (shifts != null) {
            for (Map<String, Object> s : shifts) {
                Row row = sh.createRow(r++);
                cell(row, 0, str(s.get("name")), st.text);
                cell(row, 1, str(s.get("start")) + "-" + str(s.get("end")), st.text);
                cell(row, 2, num(s.get("output")), st.num);
                cell(row, 3, hours(s.get("runSec")), st.num);
                cell(row, 4, hours(s.get("idleSec")), st.num);
                cell(row, 5, hours(s.get("stopSec")), st.num);
                cell(row, 6, pct(s.get("availability")), st.num);
            }
        }

        Map<String, Object> totals = (Map<String, Object>) daily.get("totals");
        if (totals != null) {
            Row row = sh.createRow(r++);
            cell(row, 0, "合计", st.bold);
            cell(row, 1, "", st.bold);
            cell(row, 2, num(totals.get("output")), st.boldNum);
            cell(row, 3, hours(totals.get("runSec")), st.boldNum);
            cell(row, 4, hours(totals.get("idleSec")), st.boldNum);
            cell(row, 5, hours(totals.get("stopSec")), st.boldNum);
            cell(row, 6, pct(totals.get("availability")), st.boldNum);
        }
        widths(sh, 16, 16, 12, 11, 11, 11, 10);
        sh.createFreezePane(0, 3);
    }

    private static void sheetDevices(Workbook wb, Styles st, Map<String, Object> daily,
                                     String date) {
        Sheet sh = wb.createSheet("设备明细");
        int r = 0;
        r = title(sh, st, r, "设备产量明细 " + date, 9);

        List<Map<String, Object>> shifts = (List<Map<String, Object>>) daily.get("shifts");
        int shiftCount = shifts == null ? 0 : shifts.size();

        Row head = sh.createRow(r++);
        String[] fixed = {"设备编号", "设备名称", "位置", "产量合计"};
        for (int i = 0; i < fixed.length; i++) {
            head.createCell(i).setCellValue(fixed[i]);
            head.getCell(i).setCellStyle(st.head);
        }
        int c = fixed.length;
        for (int i = 0; i < shiftCount; i++) {
            Map<String, Object> s = shifts.get(i);
            head.createCell(c).setCellValue(str(s.get("name")) + "产量");
            head.getCell(c).setCellStyle(st.head);
            c++;
        }
        String[] tail = {"运行(h)", "停机(h)", "稼动率", "数据完整度"};
        for (String t : tail) {
            head.createCell(c).setCellValue(t);
            head.getCell(c).setCellStyle(st.head);
            c++;
        }

        List<Map<String, Object>> devices = (List<Map<String, Object>>) daily.get("devices");
        double expectedSec = 0;
        Object win = daily.get("window");
        if (win instanceof Map<?, ?> w) {
            Object f = w.get("from");
            Object t = w.get("to");
            if (f instanceof Number fn && t instanceof Number tn) {
                expectedSec = Math.max(1, (tn.doubleValue() - fn.doubleValue()) / 1000d);
            }
        }
        if (devices != null) {
            for (Map<String, Object> d : devices) {
                Row row = sh.createRow(r++);
                int col = 0;
                cell(row, col++, str(d.get("code")), st.text);
                cell(row, col++, str(d.get("name")), st.text);
                cell(row, col++, str(d.get("location")), st.text);
                cell(row, col++, num(d.get("output")), st.num);

                Map<String, Object> so = (Map<String, Object>) d.get("shiftOutput");
                for (int i = 0; i < shiftCount; i++) {
                    String id = str(shifts.get(i).get("id"));
                    cell(row, col++, num(so == null ? 0 : so.get(id)), st.num);
                }
                cell(row, col++, hours(d.get("runSec")), st.num);
                cell(row, col++, hours(d.get("stopSec")), st.num);
                cell(row, col++, pct(d.get("availability")), st.num);
                cell(row, col, completeness(d, expectedSec), st.num);
            }
        }

        for (int i = 0; i < fixed.length + shiftCount + tail.length; i++) {
            sh.setColumnWidth(i, i == 1 ? 20 * 256 : 12 * 256);
        }
        sh.createFreezePane(0, 2);
    }

    private static void sheetStops(Workbook wb, Styles st, Map<String, Object> daily, String date) {
        Sheet sh = wb.createSheet("停机原因");
        int r = 0;
        r = title(sh, st, r, "停机原因统计 " + date, 4);

        Row head = sh.createRow(r++);
        String[] cols = {"原因", "次数", "累计停机(分钟)", "涉及设备"};
        for (int i = 0; i < cols.length; i++) {
            head.createCell(i).setCellValue(cols[i]);
            head.getCell(i).setCellStyle(st.head);
        }

        List<Map<String, Object>> reasons = (List<Map<String, Object>>) daily.get("stopReasons");
        if (reasons == null || reasons.isEmpty()) {
            Row row = sh.createRow(r++);
            cell(row, 0, "当天没有停机告警记录", st.text);
            cell(row, 1, "", st.text);
            cell(row, 2, "", st.text);
            cell(row, 3, "", st.text);
        } else {
            for (Map<String, Object> m : reasons) {
                Row row = sh.createRow(r++);
                cell(row, 0, str(m.get("reason")), st.text);
                cell(row, 1, num(m.get("count")), st.num);
                cell(row, 2, num(m.get("totalMin")), st.num);
                Object devs = m.get("devices");
                String joined = devs instanceof List<?> l ? String.join("、", l.stream()
                        .map(String::valueOf).toList()) : "";
                cell(row, 3, joined, st.text);
            }
        }
        widths(sh, 34, 10, 18, 40);
        sh.createFreezePane(0, 2);
    }

    /* ==================== 小工具 ==================== */

    private static int title(Sheet sh, Styles st, int rowIdx, String text, int span) {
        Row row = sh.createRow(rowIdx++);
        Cell c = row.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(st.title);
        sh.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(
                rowIdx - 1, rowIdx - 1, 0, Math.max(0, span - 1)));
        return rowIdx + 1;              // 标题下面空一行，打印好看
    }

    private static void cell(Row row, int col, String v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v == null ? "" : v);
        c.setCellStyle(style);
    }

    private static void cell(Row row, int col, double v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v);
        c.setCellStyle(style);
    }

    private static void widths(Sheet sh, int... w) {
        for (int i = 0; i < w.length; i++) {
            sh.setColumnWidth(i, w[i] * 256);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static double num(Object o) {
        return o instanceof Number n ? Math.round(n.doubleValue() * 10d) / 10d : 0d;
    }

    private static double hours(Object o) {
        return o instanceof Number n ? Math.round(n.doubleValue() / 360d) / 10d : 0d;
    }

    private static String pct(Object o) {
        double v = num(o);
        return v < 0 ? "--" : String.format("%.1f%%", v);
    }

    /** 已记录时长 ÷ 应有时间，低于 80% 说明数据不连续，报表上必须能看出来 */
    private static String completeness(Map<String, Object> d, double expectedSec) {
        Object cs = d.get("countedSec");
        if (!(cs instanceof Number n) || n.doubleValue() <= 0 || expectedSec <= 0) {
            return "--";
        }
        return String.format("%.0f%%", Math.min(100d, n.doubleValue() / expectedSec * 100));
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle head;
        final CellStyle text;
        final CellStyle num;
        final CellStyle bold;
        final CellStyle boldNum;

        Styles(Workbook wb) {
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title = wb.createCellStyle();
            title.setFont(titleFont);

            Font headFont = wb.createFont();
            headFont.setBold(true);
            head = wb.createCellStyle();
            head.setFont(headFont);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            head.setAlignment(HorizontalAlignment.CENTER);

            text = wb.createCellStyle();
            text.setBorderBottom(BorderStyle.HAIR);

            num = wb.createCellStyle();
            num.setBorderBottom(BorderStyle.HAIR);
            num.setAlignment(HorizontalAlignment.RIGHT);

            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            bold = wb.createCellStyle();
            bold.setFont(boldFont);
            boldNum = wb.createCellStyle();
            boldNum.setFont(boldFont);
            boldNum.setAlignment(HorizontalAlignment.RIGHT);
        }
    }
}
