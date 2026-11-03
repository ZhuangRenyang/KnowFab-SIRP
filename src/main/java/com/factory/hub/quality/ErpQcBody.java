package com.factory.hub.quality;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 质量域接口的请求体解析与响应封装。
 * <p>
 * <b>为什么单独一个类</b>：这些 {@code str / longOf / decOf} 以前在质量域的四个类里各抄一份
 * （控制器解析入参、服务解释数量、视图组装）。抄三份的后果不是代码丑，是<b>行为不一致</b>——
 * 同一个字段在这里 {@code trim} 了、在那里没 trim，最后数据库里出现两个看起来一样的值却不相等。
 * <p>
 * 全站约定：失败一律 <b>200 + {@code ok:false}</b>，把后端那句人话直接给前端弹出来。
 */
public final class ErpQcBody {

    private ErpQcBody() {
    }

    /** 空串按 null：前端清空输入框传的是 ""，落库要是空串，{@code is null} 的查询就查不到它 */
    public static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    public static Long longOf(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        String s = str(o);
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Integer intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        String s = str(o);
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal decOf(Object o) {
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = str(o);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 数量（件数/批量）：统一 4 位小数，与库存口径一致。
     * <p>
     * 不统一的话会出这种怪事：{@code 8} 与 {@code 8.0000} 在 {@code BigDecimal.equals} 下不相等，
     * 于是"待处置 0 件"判定不出来，记录永远留在待处置队列里。
     */
    public static BigDecimal qtyOf(Object o) {
        BigDecimal v = decOf(o);
        return v == null ? BigDecimal.ZERO : v.setScale(4, RoundingMode.HALF_UP);
    }

    public static boolean boolOf(Object o, boolean fallback) {
        if (o instanceof Boolean b) {
            return b;
        }
        String s = str(o);
        if (s == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "是".equals(s);
    }

    /** 成功响应（无附加数据） */
    public static Map<String, Object> ok(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", message);
        return m;
    }

    /** 成功响应 + 一个数据对象 */
    public static Map<String, Object> ok(String message, Object data) {
        Map<String, Object> m = ok(message);
        m.put("data", data);
        return m;
    }

    public static Map<String, Object> fail(Exception e) {
        return fail("BAD_REQUEST", e);
    }

    /**
     * @param code 给前端做分支用：{@code NO_AQL_PLAN}（主数据缺，去补方案）
     *             与 {@code BAD_REQUEST}（输入错，改输入框）要做的事完全不同
     */
    public static Map<String, Object> fail(String code, Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("code", code);
        m.put("message", e == null || e.getMessage() == null ? "操作失败" : e.getMessage());
        return m;
    }
}
