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
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 生产月报 + 电费月报（DH-16）。
 * <p>
 * 客户交月报时原来要跑两个页面截图：产量在「报表」，电费在「能耗」。
 * 这里把两边合成一个 xlsx，财务和车间拿到的是同一份数、同一个口径。
 * <p>
 * 电费 sheet 拆成「电度 / 基本 / 力调 / 合计」四段：
 * 电度和基本是系统能算的，力调（功率因数调整）系统没有采集功率因数，
 * <b>不编数字</b>——写成一行说明，让客户按供电局结算单人工核对。
 * 这比算一个看起来专业但错的数要安全。
 */
public final class MonthExcel {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private MonthExcel() {
    }

    @SuppressWarnings("unchecked")
    public static byte[] build(Map<String, Object> data) throws IOException {
        Map<String, Object> prod = (Map<String, Object>) data.get("production");
        Map<String, Object> energy = (Map<String, Object>) data.get("energy");
        Map<String, Object> demand = (Map<String, Object>) data.get("demand");
        try (Workbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            sheetSummary(wb, st, prod, energy, demand);
            sheetDaily(wb, st, prod);
            sheetDevices(wb, st, prod);
            sheetPower(wb, st, energy, demand, prod);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    /* ==================== 1. 月度汇总 ==================== */

    @SuppressWarnings("unchecked")
    private static void sheetSummary(Workbook wb, Styles st, Map<String, Object> prod,
                                     Map<String, Object> energy, Map<String, Object> demand) {
        Sheet sh = wb.createSheet("月度汇总");
        String month = str(prod.get("month"));
        int r = 0;
        r = title(sh, st, r, "生产月报 " + month, 3);
        r = note(sh, st, r, "统计区间：" + str(prod.get("fromText")) + " ~ " + str(prod.get("toText"))
                + (Boolean.TRUE.equals(prod.get("complete")) ? "（全月）" : "（当月累计，截至今日）"));

        Map<String, Object> t = map(prod.get("totals"));
        double output = num(t.get("output"));
        int activeDays = (int) num(prod.get("activeDays"));

        r++;
        r = head(sh, st, r, "指标", "数值", "说明");
        r = kv(sh, st, r, "产量", output, st.intNum, "件");
        r = kv(sh, st, r, "生产天数", activeDays, st.intNum,
                "共 " + (int) num(prod.get("days")) + " 天，有数据的天数");
        r = kv(sh, st, r, "日均产量", activeDays > 0 ? output / activeDays : 0d, st.num,
                activeDays > 0 ? "产量 ÷ 生产天数" : "本月还没有产量数据");
        r = blank(sh, r);
        r = head(sh, st, r, "运行 / 停机", "小时", "口径");
        r = kv(sh, st, r, "运行时长", hours(t.get("runSec")), st.num, "设备处于运行状态");
        r = kv(sh, st, r, "待机时长", hours(t.get("idleSec")), st.num, "通电未生产");
        r = kv(sh, st, r, "停机时长", hours(t.get("stopSec")), st.num, "设备停机");
        r = kv(sh, st, r, "报警时长", hours(t.get("alarmSec")), st.num, "报警状态");
        r = kv(sh, st, r, "已记录时长", hours(t.get("countedSec")), st.num, "五项之和");
        r = kvText(sh, st, r, "稼动率", pct(t.get("availability")), "运行 ÷ 已记录时长");
        r = blank(sh, r);

        double kwh = num(energy.get("kwh"));
        double energyCost = num(energy.get("cost"));
        Map<String, Object> bill = map(demand.get("billing"));
        double basicCost = num(bill.get("cost"));
        double totalCost = energyCost + basicCost;

        r = head(sh, st, r, "能耗 / 电费", "数值", "口径");
        r = kv(sh, st, r, "用电量", kwh, st.num, "kWh，含当前小时实时值");
        r = kv(sh, st, r, "电度电费", energyCost, st.money,
                "分时电价：" + str(energy.getOrDefault("tariffName", "")));
        r = kv(sh, st, r, "基本电费", basicCost, st.money,
                str(bill.get("modeText")) + "（" + str(bill.get("formula")) + "）");
        r = kv(sh, st, r, "电费合计", totalCost, st.boldMoney, "电度 + 基本，未含力调");
        r = kv(sh, st, r, "单位产品电耗", unit(kwh, output), st.num3,
                output > 0 ? "kWh/件" : "无产量，算不出单耗");
        r = kv(sh, st, r, "单位产品电费", unit(totalCost, output), st.money,
                output > 0 ? "元/件" : "无产量，算不出单件电费");

        r = note(sh, st, r + 1, "力调电费（功率因数调整）系统未采集功率因数，请按供电局结算单核对，详见「电费」sheet。");
        r = note(sh, st, r, "导出时间：" + LocalDateTime.now().format(TS));

        widths(sh, 22, 16, 46);
        sh.createFreezePane(0, 0);
    }

    /* ==================== 2. 每日明细 ==================== */

    @SuppressWarnings("unchecked")
    private static void sheetDaily(Workbook wb, Styles st, Map<String, Object> prod) {
        Sheet sh = wb.createSheet("每日明细");
        int r = 0;
        r = title(sh, st, r, "每日产量 " + str(prod.get("month")), 7);

        Row head = sh.createRow(r++);
        String[] cols = {"日期", "产量(件)", "运行(h)", "待机(h)", "停机(h)", "报警(h)", "稼动率"};
        header(sh, st, head, cols);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) prod.get("daily");
        if (rows != null) {
            for (Map<String, Object> m : rows) {
                Row row = sh.createRow(r++);
                cell(row, 0, str(m.get("date")), st.text);
                cell(row, 1, num(m.get("output")), st.intNum);
                cell(row, 2, hours(m.get("runSec")), st.num);
                cell(row, 3, hours(m.get("idleSec")), st.num);
                cell(row, 4, hours(m.get("stopSec")), st.num);
                cell(row, 5, hours(m.get("alarmSec")), st.num);
                cell(row, 6, pct(m.get("availability")), st.text);
            }
        }

        Map<String, Object> t = map(prod.get("totals"));
        Row row = sh.createRow(r++);
        cell(row, 0, "合计", st.bold);
        cell(row, 1, num(t.get("output")), st.boldInt);
        cell(row, 2, hours(t.get("runSec")), st.boldNum);
        cell(row, 3, hours(t.get("idleSec")), st.boldNum);
        cell(row, 4, hours(t.get("stopSec")), st.boldNum);
        cell(row, 5, hours(t.get("alarmSec")), st.boldNum);
        cell(row, 6, pct(t.get("availability")), st.bold);

        widths(sh, 14, 12, 11, 11, 11, 11, 10);
        sh.createFreezePane(0, 2);
    }

    /* ==================== 3. 设备月产量 ==================== */

    @SuppressWarnings("unchecked")
    private static void sheetDevices(Workbook wb, Styles st, Map<String, Object> prod) {
        Sheet sh = wb.createSheet("设备月产量");
        int r = 0;
        r = title(sh, st, r, "设备月产量 " + str(prod.get("month")), 8);

        Row head = sh.createRow(r++);
        String[] cols = {"设备编号", "设备名称", "位置", "产量(件)",
                "运行(h)", "停机(h)", "记录时长(h)", "稼动率"};
        header(sh, st, head, cols);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) prod.get("devices");
        if (rows == null || rows.isEmpty()) {
            Row row = sh.createRow(r++);
            cell(row, 0, "本月还没有设备产量数据", st.text);
        } else {
            for (Map<String, Object> m : rows) {
                Row row = sh.createRow(r++);
                cell(row, 0, str(m.get("code")), st.text);
                cell(row, 1, str(m.get("name")), st.text);
                cell(row, 2, str(m.get("location")), st.text);
                cell(row, 3, num(m.get("output")), st.intNum);
                cell(row, 4, hours(m.get("runSec")), st.num);
                cell(row, 5, hours(m.get("stopSec")), st.num);
                cell(row, 6, hours(m.get("countedSec")), st.num);
                cell(row, 7, pct(m.get("availability")), st.text);
            }
        }
        widths(sh, 16, 22, 16, 12, 11, 11, 13, 10);
        sh.createFreezePane(0, 2);
    }

    /* ==================== 4. 电费 ==================== */

    @SuppressWarnings("unchecked")
    private static void sheetPower(Workbook wb, Styles st, Map<String, Object> energy,
                                   Map<String, Object> demand, Map<String, Object> prod) {
        Sheet sh = wb.createSheet("电费");
        int r = 0;
        r = title(sh, st, r, "电费月报 " + str(energy.get("month")), 4);
        r = note(sh, st, r, "统计区间：" + str(energy.get("fromText")) + " ~ " + str(energy.get("toText"))
                + (Boolean.TRUE.equals(energy.get("complete")) ? "（全月）" : "（当月累计，截至今日）"));
        r = note(sh, st, r, "电价方案：" + str(energy.getOrDefault("tariffName", "")));

        /* ---- 一、电度电费 ---- */
        r = section(sh, st, r + 1, "一、电度电费", 4);
        r = head(sh, st, r, "时段", "电量(kWh)", "单价(元/kWh)", "电费(元)");
        List<Map<String, Object>> segs = (List<Map<String, Object>>) energy.get("segments");
        if (segs != null) {
            for (Map<String, Object> s : segs) {
                double kwh = num(s.get("kwh"));
                if (kwh <= 0 && num(s.get("cost")) <= 0) {
                    continue;                 // 没用到的时段不占行，报表更干净
                }
                Row row = sh.createRow(r++);
                cell(row, 0, str(s.get("name")), st.text);
                cell(row, 1, kwh, st.num);
                cell(row, 2, num(s.get("price")), st.money3);
                cell(row, 3, num(s.get("cost")), st.money);
            }
        }
        Row sub = sh.createRow(r++);
        cell(sub, 0, "电度电费小计", st.bold);
        cell(sub, 1, num(energy.get("kwh")), st.boldNum);
        cell(sub, 2, "", st.bold);
        cell(sub, 3, num(energy.get("cost")), st.boldMoney);

        /* ---- 二、基本电费 ---- */
        Map<String, Object> cfg = map(demand.get("config"));
        Map<String, Object> bill = map(demand.get("billing"));
        Map<String, Object> max = map(demand.get("monthMax"));
        boolean byCapacity = "CAPACITY".equals(str(bill.get("mode")));

        r = section(sh, st, r + 1, "二、基本电费", 4);
        r = head(sh, st, r, "项目", "数值", "单位", "说明");
        r = kvUnitText(sh, st, r, "计费方式", str(bill.get("modeText")), "", "由系统按更省的方式计算");
        if (byCapacity) {
            r = kvUnit(sh, st, r, "变压器容量", num(cfg.get("transformerKva")), "kVA", "按容量计费");
            r = kvUnit(sh, st, r, "容量单价", num(cfg.get("capacityPrice")), "元/kVA·月", "");
            r = kvUnit(sh, st, r, "基本电费", num(bill.get("cost")), "元", str(bill.get("formula")));
        } else {
            r = kvUnit(sh, st, r, "合同需量", num(cfg.get("contractDemandKw")), "kW", "超出部分按供电局规则加收");
            r = kvUnit(sh, st, r, "当月最大需量", num(max.get("kw")), "kW",
                    str(max.get("atText")));
            r = kvUnit(sh, st, r, "需量单价", num(cfg.get("demandPrice")), "元/kW·月", "");
            r = kvUnit(sh, st, r, "基本电费", num(bill.get("cost")), "元", str(bill.get("formula")));
        }

        /* ---- 三、力调电费：不编数字 ---- */
        r = section(sh, st, r + 1, "三、力调电费（功率因数调整）", 4);
        r = note(sh, st, r, "本系统未采集功率因数，力调电费请按供电局结算单人工核对后填入：__________ 元。");
        r = note(sh, st, r, "（不自动计算的原因：功率因数要用专用电表或有功/无功双路计量，"
                + "用估算值会让整张月报失去对账价值。）");

        /* ---- 四、合计 ---- */
        double energyCost = num(energy.get("cost"));
        double basicCost = num(bill.get("cost"));
        double output = num(map(prod.get("totals")).get("output"));
        if (output <= 0) {
            output = num(prod.get("output"));     // 容错：调用方直接放 totals 的情况
        }
        double totalCost = energyCost + basicCost;

        r = section(sh, st, r + 1, "四、合计", 4);
        r = head(sh, st, r, "项目", "金额(元)", "", "口径");
        r = kvUnit(sh, st, r, "电度电费", energyCost, "元", "按分时电价计算");
        r = kvUnit(sh, st, r, "基本电费", basicCost, "元", str(bill.get("modeText")));
        r = kvUnit(sh, st, r, "力调电费", 0d, "元", "未计，见第三段，按结算单人工补");
        Row total = sh.createRow(r++);
        cell(total, 0, "本月电费合计（未含力调）", st.bold);
        cell(total, 1, totalCost, st.boldMoney);
        cell(total, 2, "元", st.bold);
        cell(total, 3, "", st.bold);

        r = blank(sh, r);
        r = kvUnit(sh, st, r, "本月用电量", num(energy.get("kwh")), "kWh", "含当前小时实时值");
        r = kvUnit(sh, st, r, "本月产量", output, "件", "与「月度汇总」同一口径");
        r = kvUnit(sh, st, r, "单位产品电耗", unit(num(energy.get("kwh")), output),
                "kWh/件", output > 0 ? "" : "无产量，算不出单耗");
        r = kvUnit(sh, st, r, "单位产品电费", unit(totalCost, output),
                "元/件", output > 0 ? "" : "无产量，算不出单件电费");

        r = note(sh, st, r + 1, "数据说明：电量取能耗小时表（含当前小时内存实时值），"
                + "产量取生产小时表；两表均为本系统采集，未做人工调整。");
        r = note(sh, st, r, "导出时间：" + LocalDateTime.now().format(TS));

        widths(sh, 24, 16, 14, 50);
        sh.createFreezePane(0, 0);
    }

    /* ==================== 小工具 ==================== */

    private static int title(Sheet sh, Styles st, int rowIdx, String text, int span) {
        Row row = sh.createRow(rowIdx++);
        Cell c = row.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(st.title);
        sh.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, Math.max(0, span - 1)));
        return rowIdx + 1;
    }

    private static int section(Sheet sh, Styles st, int rowIdx, String text, int span) {
        Row row = sh.createRow(rowIdx++);
        Cell c = row.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(st.section);
        sh.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, Math.max(0, span - 1)));
        return rowIdx;
    }

    private static int note(Sheet sh, Styles st, int rowIdx, String text) {
        Row row = sh.createRow(rowIdx++);
        Cell c = row.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(st.note);
        return rowIdx;
    }

    private static int blank(Sheet sh, int rowIdx) {
        return rowIdx + 1;
    }

    private static int head(Sheet sh, Styles st, int rowIdx, String... cols) {
        Row row = sh.createRow(rowIdx++);
        header(sh, st, row, cols);
        return rowIdx;
    }

    private static void header(Sheet sh, Styles st, Row row, String[] cols) {
        for (int i = 0; i < cols.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(cols[i]);
            c.setCellStyle(st.head);
        }
    }

    private static int kv(Sheet sh, Styles st, int rowIdx, String label, double value,
                          CellStyle style, String note) {
        Row row = sh.createRow(rowIdx++);
        cell(row, 0, label, st.text);
        cell(row, 1, value, style);
        cell(row, 2, note, st.text);
        return rowIdx;
    }

    private static int kvText(Sheet sh, Styles st, int rowIdx, String label, String value, String note) {
        Row row = sh.createRow(rowIdx++);
        cell(row, 0, label, st.text);
        cell(row, 1, value, st.text);
        cell(row, 2, note, st.text);
        return rowIdx;
    }

    private static int kvUnitText(Sheet sh, Styles st, int rowIdx, String label, String value,
                                  String unit, String note) {
        Row row = sh.createRow(rowIdx++);
        cell(row, 0, label, st.text);
        cell(row, 1, value, st.text);
        cell(row, 2, unit, st.text);
        cell(row, 3, note, st.text);
        return rowIdx;
    }

    private static int kvUnit(Sheet sh, Styles st, int rowIdx, String label, double value,
                              String unit, String note) {
        Row row = sh.createRow(rowIdx++);
        cell(row, 0, label, st.text);
        cell(row, 1, value, st.num);
        cell(row, 2, unit, st.text);
        cell(row, 3, note, st.text);
        return rowIdx;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static double num(Object o) {
        return o instanceof Number n ? Math.round(n.doubleValue() * 10d) / 10d : 0d;
    }

    /** 单位产品指标：没有产量就是 0（旁边会写"算不出"），有产量保留 3 位，别把浮点尾巴带进 Excel */
    private static double unit(double total, double output) {
        if (output <= 0d) {
            return 0d;
        }
        return Math.round(total / output * 1000d) / 1000d;
    }

    private static double hours(Object o) {
        return o instanceof Number n ? Math.round(n.doubleValue() / 360d) / 10d : 0d;
    }

    private static String pct(Object o) {
        double v = num(o);
        return v < 0 ? "--" : String.format("%.1f%%", v);
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle section;
        final CellStyle head;
        final CellStyle text;
        final CellStyle note;
        final CellStyle num;
        final CellStyle intNum;
        final CellStyle num3;
        final CellStyle money;
        final CellStyle money3;
        final CellStyle bold;
        final CellStyle boldNum;
        final CellStyle boldInt;
        final CellStyle boldMoney;

        Styles(Workbook wb) {
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title = wb.createCellStyle();
            title.setFont(titleFont);

            Font sectionFont = wb.createFont();
            sectionFont.setBold(true);
            section = wb.createCellStyle();
            section.setFont(sectionFont);
            section.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            section.setFillPattern(FillPatternType.SOLID_FOREGROUND);

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

            note = wb.createCellStyle();
            note.setWrapText(false);

            num = numberStyle(wb, "#,##0.0");
            intNum = numberStyle(wb, "#,##0");
            num3 = numberStyle(wb, "0.000");
            money = numberStyle(wb, "#,##0.00");
            money3 = numberStyle(wb, "0.000");

            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            bold = wb.createCellStyle();
            bold.setFont(boldFont);

            boldNum = numberStyle(wb, "#,##0.0");
            boldNum.setFont(boldFont);
            boldInt = numberStyle(wb, "#,##0");
            boldInt.setFont(boldFont);
            boldMoney = numberStyle(wb, "#,##0.00");
            boldMoney.setFont(boldFont);
        }

        private static CellStyle numberStyle(Workbook wb, String fmt) {
            CellStyle s = wb.createCellStyle();
            s.setDataFormat(wb.createDataFormat().getFormat(fmt));
            s.setAlignment(HorizontalAlignment.RIGHT);
            s.setBorderBottom(BorderStyle.HAIR);
            return s;
        }
    }
}
