package com.factory.hub.erp;

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
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 经营报表导出（DH-57）。
 * <p>
 * 为什么不用 CSV：厂里交报表是要打印、要贴进月度总结的，CSV 双击打开按 GBK 解 UTF-8 是乱码，
 * 一个"导出后打不开"的印象能把前面所有工作抵消掉（同 ProdExcel 的判断）。
 * <p>
 * 这里用一个通用的表格构建器，四类报表只是"列定义 + 数据"不同；
 * 每张表都带标题、冻结表头、金额右对齐加千分位、合计行加粗——
 * 客户拿到就能直接打印，不用再调半小时格式。
 */
public final class ErpReportExcel {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 列类型：文本 / 数量 / 金额 / 百分比 / 日期（epoch 毫秒） / 整数 */
    private static final int TEXT = 0;
    private static final int QTY = 1;
    private static final int MONEY = 2;
    private static final int PCT = 3;
    private static final int DATE = 4;
    private static final int INT = 5;

    private ErpReportExcel() {
    }

    /** 列定义 */
    private record Col(String head, String key, int width, int kind) {
        static Col t(String h, String k, int w) { return new Col(h, k, w, TEXT); }
        static Col q(String h, String k, int w) { return new Col(h, k, w, QTY); }
        static Col m(String h, String k, int w) { return new Col(h, k, w, MONEY); }
        static Col p(String h, String k, int w) { return new Col(h, k, w, PCT); }
        static Col d(String h, String k, int w) { return new Col(h, k, w, DATE); }
        static Col i(String h, String k, int w) { return new Col(h, k, w, INT); }
    }

    /* ==================== 经营概况 ==================== */

    @SuppressWarnings("unchecked")
    public static byte[] overview(Map<String, Object> d) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            String range = d.get("fromDate") + " ~ " + d.get("toDate");

            Sheet kpi = wb.createSheet("经营概况");
            int r = title(kpi, st, 0, "经营概况 " + range, 4);
            r = header(kpi, st, r, List.of("指标", "数值", "说明"));
            r = kpiRow(kpi, st, r, "本期采购入库额（净）", num(d.get("purchaseAmount")), "采购入库 − 采购退货");
            r = kpiRow(kpi, st, r, "本期销售出库额（净）", num(d.get("salesAmount")), "销售出库 − 销售退货");
            r = kpiRow(kpi, st, r, "本期生产入库量", num(d.get("prodInQty")), "设备计数自动入库合计（各计量单位混计）");
            r = kpiRow(kpi, st, r, "当前库存金额", num(d.get("stockValue")), "按商品采购价估值，时点数不随区间变化");
            r = kpiRow(kpi, st, r, "应收未收", num(d.get("arOutstanding")), "已发货未收款，含逾期 " + plain(d.get("arOverdue")));
            r = kpiRow(kpi, st, r, "应付未付", num(d.get("apOutstanding")), "已入库未付款，含逾期 " + plain(d.get("apOverdue")));
            r = kpiRow(kpi, st, r, "本期采购订单", num(d.get("poCount")), "下单金额 " + plain(d.get("poOrderAmount")));
            r = kpiRow(kpi, st, r, "本期销售订单", num(d.get("soCount")), "下单金额 " + plain(d.get("soOrderAmount")));
            widths(kpi, 24, 18, 46);
            kpi.createFreezePane(0, 3);

            table(wb, st, "采购销售趋势", "每日采购入库 / 销售出库（净额）",
                    List.of(Col.d("日期", "date", 14), Col.m("采购入库额", "purchase", 16),
                            Col.m("销售出库额", "sales", 16)),
                    (List<Map<String, Object>>) d.get("trend"), null, null);

            table(wb, st, "客户排行", "本期客户排行（按出库净额）",
                    List.of(Col.t("客户", "name", 26), Col.m("金额", "amount", 16)),
                    (List<Map<String, Object>>) d.get("topCustomers"), null, null);

            table(wb, st, "供应商排行", "本期供应商排行（按入库净额）",
                    List.of(Col.t("供应商", "name", 26), Col.m("金额", "amount", 16)),
                    (List<Map<String, Object>>) d.get("topSuppliers"), null, null);

            table(wb, st, "商品动销排行", "本期商品动销排行（按金额）",
                    List.of(Col.t("编码", "code", 14), Col.t("名称", "name", 22),
                            Col.t("规格", "spec", 18), Col.t("单位", "unit", 8),
                            Col.q("入库数量", "inQty", 14), Col.q("出库数量", "outQty", 14),
                            Col.m("金额", "amount", 16)),
                    (List<Map<String, Object>>) d.get("topSkus"), null, null);

            return bytes(wb);
        }
    }

    /* ==================== 库存状况 ==================== */

    @SuppressWarnings("unchecked")
    public static byte[] stock(Map<String, Object> d) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            String range = d.get("fromDate") + " ~ " + d.get("toDate");
            String wh = String.valueOf(d.getOrDefault("warehouseName", "全部仓库"));
            Map<String, Object> totals = (Map<String, Object>) d.get("totals");

            Sheet sh = wb.createSheet("库存状况");
            int r = title(sh, st, 0, "库存状况（进销存） " + range + " · " + wh, 9);
            r = note(sh, st, r, "说明：期初 + 本期入库 − 本期出库 = 期末；库存金额按商品采购价估值。"
                    + "数量合计跨计量单位无意义，只作参考。", 9);

            List<Col> cols = List.of(
                    Col.t("商品编码", "code", 14), Col.t("商品名称", "name", 22),
                    Col.t("规格", "spec", 16), Col.t("单位", "unit", 8),
                    Col.t("仓库", "warehouseName", 14),
                    Col.q("期初数量", "opening", 14), Col.q("本期入库", "inQty", 14),
                    Col.q("本期出库", "outQty", 14), Col.q("期末数量", "closing", 14),
                    Col.m("期末金额", "amount", 16));
            r = header(sh, st, r, cols.stream().map(Col::head).toList());
            List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("rows");
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    r = bodyRow(sh, st, r, cols, row);
                }
            }
            Map<String, Object> tot = new java.util.LinkedHashMap<>();
            if (totals != null) {
                tot.put("code", "合计");
                tot.put("name", (totals.get("skuCount") == null ? "0" : totals.get("skuCount")) + " 个商品/库位");
                tot.put("opening", "");
                tot.put("inQty", "");
                tot.put("outQty", "");
                tot.put("closing", "");
                tot.put("amount", totals.get("amount"));
            }
            totalRow(sh, st, r, cols, tot);
            for (int i = 0; i < cols.size(); i++) {
                sh.setColumnWidth(i, cols.get(i).width() * 256);
            }
            sh.createFreezePane(0, 3);
            return bytes(wb);
        }
    }

    /* ==================== 出入库统计 ==================== */

    @SuppressWarnings("unchecked")
    public static byte[] flows(Map<String, Object> d) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            String range = d.get("fromDate") + " ~ " + d.get("toDate");
            Map<String, Object> totals = (Map<String, Object>) d.get("totals");

            List<Col> typeCols = List.of(Col.t("单据类型", "typeLabel", 16), Col.i("单据张数", "bills", 12),
                    Col.q("入库数量", "inQty", 14), Col.q("出库数量", "outQty", 14),
                    Col.m("入库金额", "inAmount", 16), Col.m("出库金额", "outAmount", 16),
                    Col.m("净额", "netAmount", 16));
            Map<String, Object> typeTotal = new java.util.LinkedHashMap<>();
            if (totals != null) {
                typeTotal.put("typeLabel", "合计");
                typeTotal.put("bills", totals.get("bills"));
                typeTotal.put("inQty", totals.get("inQty"));
                typeTotal.put("outQty", totals.get("outQty"));
                typeTotal.put("inAmount", totals.get("inAmount"));
                typeTotal.put("outAmount", totals.get("outAmount"));
                typeTotal.put("netAmount", totals.get("netAmount"));
            }
            table(wb, st, "按单据类型", "出入库统计（按单据类型） " + range, typeCols,
                    (List<Map<String, Object>>) d.get("byType"), typeTotal, null);

            table(wb, st, "按仓库", "出入库统计（按仓库） " + range,
                    List.of(Col.t("仓库", "warehouseName", 16), Col.i("单据张数", "bills", 12),
                            Col.q("入库数量", "inQty", 14), Col.q("出库数量", "outQty", 14),
                            Col.m("入库金额", "inAmount", 16), Col.m("出库金额", "outAmount", 16)),
                    (List<Map<String, Object>>) d.get("byWarehouse"), null, null);

            table(wb, st, "按日期", "每日出入库 " + range,
                    List.of(Col.d("日期", "date", 14), Col.q("入库数量", "inQty", 14),
                            Col.q("出库数量", "outQty", 14),
                            Col.m("入库金额", "inAmount", 16), Col.m("出库金额", "outAmount", 16)),
                    (List<Map<String, Object>>) d.get("daily"), null, null);

            table(wb, st, "商品动销", "商品动销排行（按金额）",
                    List.of(Col.t("编码", "code", 14), Col.t("名称", "name", 22),
                            Col.t("规格", "spec", 16), Col.t("单位", "unit", 8),
                            Col.q("入库数量", "inQty", 14), Col.q("出库数量", "outQty", 14),
                            Col.m("金额", "amount", 16)),
                    (List<Map<String, Object>>) d.get("topSkus"), null, null);

            return bytes(wb);
        }
    }

    /* ==================== 采购 / 销售执行 ==================== */

    @SuppressWarnings("unchecked")
    public static byte[] trade(Map<String, Object> d) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            String range = d.get("fromDate") + " ~ " + d.get("toDate");
            String side = String.valueOf(d.getOrDefault("sideLabel", "采购"));
            String partnerLabel = String.valueOf(d.getOrDefault("partnerLabel", "供应商"));
            Map<String, Object> totals = (Map<String, Object>) d.get("totals");

            Map<String, Object> partnerTotal = new java.util.LinkedHashMap<>();
            if (totals != null) {
                partnerTotal.put("partnerName", "合计");
                partnerTotal.put("orders", totals.get("orders"));
                partnerTotal.put("orderAmount", totals.get("orderAmount"));
                partnerTotal.put("executedAmount", totals.get("executedAmount"));
                partnerTotal.put("openAmount", totals.get("openAmount"));
                partnerTotal.put("rate", totals.get("rate"));
            }
            table(wb, st, partnerLabel + "汇总", side + "执行汇总（按" + partnerLabel + "） " + range,
                    List.of(Col.t(partnerLabel, "partnerName", 26), Col.i("订单数", "orders", 10),
                            Col.m("下单金额", "orderAmount", 16),
                            Col.m("已执行金额", "executedAmount", 16),
                            Col.m("未执行金额", "openAmount", 16),
                            Col.p("执行率%", "rate", 12)),
                    (List<Map<String, Object>>) d.get("partners"), partnerTotal, null);

            String doneHead = "sales".equals(d.get("side")) ? "已发货金额" : "已入库金额";
            table(wb, st, "订单明细", side + "订单执行明细 " + range,
                    List.of(Col.t("订单号", "orderNo", 20), Col.d("下单日期", "orderDate", 14),
                            Col.t(partnerLabel, "partnerName", 24), Col.t("状态", "statusLabel", 12),
                            Col.m("下单金额", "orderAmount", 16), Col.m(doneHead, "executedAmount", 16),
                            Col.m("未执行金额", "openAmount", 16), Col.p("执行率%", "rate", 12)),
                    (List<Map<String, Object>>) d.get("orders"), null, null);

            return bytes(wb);
        }
    }

    /* ==================== 销售查询（DH-45，无成本列） ==================== */

    /**
     * 销售查询导出。列是刻意挑的：只给"能卖多少"，<b>没有采购价与库存金额</b>。
     * 这张表会通过微信发给客户或销售，成本列一旦在里面就等于把底价送出去。
     */
    @SuppressWarnings("unchecked")
    public static byte[] salesStock(Map<String, Object> d) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            String day = LocalDate.now(ZONE).format(DAY);
            Map<String, Object> totals = (Map<String, Object>) d.get("totals");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("rows");

            Sheet sh = wb.createSheet("库存查询");
            int r = title(sh, st, 0, "销售库存查询 " + day, 9);
            r = note(sh, st, r, "可承诺量 = 现存量 − 已确认订单未发货数量。"
                    + "数量单位见每行「单位」列，不同商品之间不要直接相加。", 9);

            List<Col> cols = List.of(
                    Col.t("商品编码", "code", 14), Col.t("商品名称", "name", 24),
                    Col.t("规格", "spec", 18), Col.t("单位", "unit", 8),
                    Col.t("类别", "category", 12),
                    Col.q("现存量", "onHand", 12), Col.q("订单占用", "allocated", 12),
                    Col.q("可承诺", "available", 12), Col.t("所在仓库", "warehouseText", 30));
            r = header(sh, st, r, cols.stream().map(Col::head).toList());
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    r = bodyRow(sh, st, r, cols, row);
                }
            }
            if (rows == null || rows.isEmpty()) {
                Row empty = sh.createRow(r++);
                cell(empty, 0, "没有符合条件的商品", st.text);
            }
            if (totals != null) {
                Map<String, Object> tot = new java.util.LinkedHashMap<>();
                tot.put("code", "合计");
                tot.put("name", (totals.get("skuCount") == null ? "0" : totals.get("skuCount")) + " 个商品");
                tot.put("onHand", totals.get("onHand"));
                tot.put("allocated", totals.get("allocated"));
                tot.put("available", totals.get("available"));
                totalRow(sh, st, r, cols, tot);
            }
            for (int i = 0; i < cols.size(); i++) {
                sh.setColumnWidth(i, cols.get(i).width() * 256);
            }
            sh.createFreezePane(0, 3);
            return bytes(wb);
        }
    }

    /* ==================== 通用表格 ==================== */

    private static void table(Workbook wb, Styles st, String sheetName, String title,
                              List<Col> cols, List<Map<String, Object>> rows,
                              Map<String, Object> totalValues, String totalLabel) {
        Sheet sh = wb.createSheet(safeName(wb, sheetName));
        int r = title(sh, st, 0, title, cols.size());
        r = header(sh, st, r, cols.stream().map(Col::head).toList());
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                r = bodyRow(sh, st, r, cols, row);
            }
        }
        if (rows == null || rows.isEmpty()) {
            Row row = sh.createRow(r++);
            cell(row, 0, "该区间没有数据", st.text);
        }
        if (totalValues != null) {
            if (totalLabel != null) {
                totalValues.putIfAbsent(cols.get(0).key(), totalLabel);
            }
            totalRow(sh, st, r, cols, totalValues);
        }
        for (int i = 0; i < cols.size(); i++) {
            sh.setColumnWidth(i, cols.get(i).width() * 256);
        }
        sh.createFreezePane(0, 3);
    }

    private static int bodyRow(Sheet sh, Styles st, int r, List<Col> cols, Map<String, Object> data) {
        Row row = sh.createRow(r);
        for (int i = 0; i < cols.size(); i++) {
            Col c = cols.get(i);
            write(row, i, c.kind(), data.get(c.key()), false, st);
        }
        return r + 1;
    }

    private static void totalRow(Sheet sh, Styles st, int r, List<Col> cols, Map<String, Object> data) {
        if (data == null) {
            return;
        }
        Row row = sh.createRow(r);
        for (int i = 0; i < cols.size(); i++) {
            Col c = cols.get(i);
            write(row, i, c.kind(), data.get(c.key()), true, st);
        }
    }

    private static void write(Row row, int col, int kind, Object v, boolean bold, Styles st) {
        if (kind == TEXT) {
            cell(row, col, v == null ? "" : String.valueOf(v), bold ? st.bold : st.text);
            return;
        }
        if (kind == DATE) {
            String s = "";
            if (v instanceof Number n) {
                s = DAY.format(Instant.ofEpochMilli(n.longValue()).atZone(ZONE));
            } else if (v != null && !String.valueOf(v).isBlank()) {
                s = String.valueOf(v);
            }
            cell(row, col, s, bold ? st.bold : st.text);
            return;
        }
        double d;
        if (v instanceof Number n) {
            d = n.doubleValue();
        } else if (v == null || String.valueOf(v).isBlank()) {
            cell(row, col, "", bold ? st.bold : st.text);   // 空值留空，别写成 0 骗人
            return;
        } else {
            try {
                d = Double.parseDouble(String.valueOf(v));
            } catch (NumberFormatException e) {
                cell(row, col, String.valueOf(v), bold ? st.bold : st.text);
                return;
            }
        }
        CellStyle style = switch (kind) {
            case MONEY -> bold ? st.boldMoney : st.money;
            case QTY -> bold ? st.boldQty : st.qty;
            case PCT -> bold ? st.boldPct : st.pct;
            default -> bold ? st.boldInt : st.intStyle;
        };
        Cell c = row.createCell(col);
        c.setCellValue(d);
        c.setCellStyle(style);
    }

    private static int title(Sheet sh, Styles st, int rowIdx, String text, int span) {
        Row row = sh.createRow(rowIdx++);
        Cell c = row.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(st.title);
        sh.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, Math.max(0, span - 1)));
        return rowIdx + 1;
    }

    private static int note(Sheet sh, Styles st, int rowIdx, String text, int span) {
        Row row = sh.createRow(rowIdx++);
        Cell c = row.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(st.note);
        sh.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, Math.max(0, span - 1)));
        return rowIdx;
    }

    private static int header(Sheet sh, Styles st, int rowIdx, List<String> heads) {
        Row head = sh.createRow(rowIdx++);
        for (int i = 0; i < heads.size(); i++) {
            head.createCell(i).setCellValue(heads.get(i));
            head.getCell(i).setCellStyle(st.head);
        }
        return rowIdx;
    }

    private static int kpiRow(Sheet sh, Styles st, int r, String name, double value, String hint) {
        Row row = sh.createRow(r);
        cell(row, 0, name, st.text);
        Cell c = row.createCell(1);
        c.setCellValue(value);
        c.setCellStyle(st.money);
        cell(row, 2, hint, st.text);
        return r + 1;
    }

    private static void cell(Row row, int col, String v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v == null ? "" : v);
        c.setCellStyle(style);
    }

    private static void widths(Sheet sh, int... w) {
        for (int i = 0; i < w.length; i++) {
            sh.setColumnWidth(i, w[i] * 256);
        }
    }

    private static double num(Object o) {
        return o instanceof Number n ? Math.round(n.doubleValue() * 100d) / 100d : 0d;
    }

    private static String plain(Object o) {
        return o instanceof Number n ? String.format("%,.2f", n.doubleValue()) : String.valueOf(o);
    }

    private static String safeName(Workbook wb, String name) {
        String n = name.replaceAll("[\\\\/*?:\\[\\]]", " ").trim();
        if (n.isEmpty()) {
            n = "Sheet" + (wb.getNumberOfSheets() + 1);
        }
        return n.length() > 28 ? n.substring(0, 28) : n;
    }

    private static byte[] bytes(Workbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle note;
        final CellStyle head;
        final CellStyle text;
        final CellStyle qty;
        final CellStyle money;
        final CellStyle pct;
        final CellStyle intStyle;
        final CellStyle bold;
        final CellStyle boldQty;
        final CellStyle boldMoney;
        final CellStyle boldPct;
        final CellStyle boldInt;

        Styles(Workbook wb) {
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title = wb.createCellStyle();
            title.setFont(titleFont);

            Font noteFont = wb.createFont();
            noteFont.setItalic(true);
            noteFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            note = wb.createCellStyle();
            note.setFont(noteFont);

            Font headFont = wb.createFont();
            headFont.setBold(true);
            head = wb.createCellStyle();
            head.setFont(headFont);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            head.setAlignment(HorizontalAlignment.CENTER);

            Font boldFont = wb.createFont();
            boldFont.setBold(true);

            text = plain(wb, null, BorderStyle.HAIR, null);
            bold = plain(wb, boldFont, BorderStyle.THIN, null);

            qty = plain(wb, null, BorderStyle.HAIR, "#,##0.####");
            money = plain(wb, null, BorderStyle.HAIR, "#,##0.00");
            pct = plain(wb, null, BorderStyle.HAIR, "0.0");
            intStyle = plain(wb, null, BorderStyle.HAIR, "0");

            boldQty = plain(wb, boldFont, BorderStyle.THIN, "#,##0.####");
            boldMoney = plain(wb, boldFont, BorderStyle.THIN, "#,##0.00");
            boldPct = plain(wb, boldFont, BorderStyle.THIN, "0.0");
            boldInt = plain(wb, boldFont, BorderStyle.THIN, "0");
        }

        private static CellStyle plain(Workbook wb, Font font, BorderStyle border, String fmt) {
            CellStyle s = wb.createCellStyle();
            if (font != null) {
                s.setFont(font);
            }
            if (border != null) {
                s.setBorderBottom(border);
            }
            if (fmt != null) {
                s.setDataFormat(wb.createDataFormat().getFormat(fmt));
                s.setAlignment(HorizontalAlignment.RIGHT);
            }
            return s;
        }
    }

    /** 供控制器命名文件用 */
    public static List<String> sheetNames(String type) {
        List<String> names = new ArrayList<>();
        switch (type) {
            case "stock" -> names.add("库存状况");
            case "flows" -> {
                names.add("按单据类型");
                names.add("按仓库");
                names.add("按日期");
                names.add("商品动销");
            }
            case "trade" -> {
                names.add("汇总");
                names.add("订单明细");
            }
            default -> {
                names.add("经营概况");
                names.add("采购销售趋势");
            }
        }
        return names;
    }
}
