package com.factory.hub.alarm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 告警限值分级的档位解析（DH-14）。
 * <p>
 * <b>为什么单独一个类：</b>分级这件事的规则散在好几个地方——
 * 判定要比较档位、校验要查档位、审计文案要写档位、通知要知道等级高低。
 * 塞进 {@link AlarmEngine} 只会让那个文件继续变大，而且各处各写一份比较逻辑，
 * 迟早出现「这里算 3 那里算 2」这种最难查的错。这里放一份，其余地方都调它。
 * <p>
 * <b>等级只有三档：</b>MINOR（预警） &lt; MAJOR（告警） &lt; CRITICAL（停机）。
 * 权重集中在这里，别在别处散着写 if。
 * <p>
 * <b>老规则怎么算：</b>{@code tiers} 为空 = 隐式单档（规则级的 level + threshold），
 * 判定结果和升级前 100% 一致，存量机器上的规则 JSON 不需要迁移。
 */
public final class AlarmTierResolver {

    /** 预警 */
    public static final String MINOR = "MINOR";
    /** 告警 */
    public static final String MAJOR = "MAJOR";
    /** 停机 */
    public static final String CRITICAL = "CRITICAL";

    private AlarmTierResolver() {
    }

    /**
     * 命中的档位。level 是等级枚举，threshold 是这一档的阈值。
     *
     * @param level     等级：MINOR / MAJOR / CRITICAL
     * @param threshold 命中档的阈值
     */
    public record TierHit(String level, Double threshold) {
    }

    /* ==================== 等级词汇 ==================== */

    /**
     * 等级权重：MINOR=1 &lt; MAJOR=2 &lt; CRITICAL=3。
     * 拿不准是不是合法等级时看返回值，0 = 不认识。
     */
    public static int rank(String level) {
        if (level == null) {
            return 0;
        }
        return switch (level.trim().toUpperCase()) {
            case CRITICAL -> 3;
            case MAJOR -> 2;
            case MINOR -> 1;
            default -> 0;
        };
    }

    /** 是不是一个合法的等级 */
    public static boolean isLevel(String level) {
        return rank(level) > 0;
    }

    /**
     * 等级的中文名。全应用只有这一份，
     * 通知标题、审计文案、大屏、规则表单下拉都读它。
     */
    public static String levelText(String level) {
        if (level == null) {
            return "告警";
        }
        return switch (level.trim().toUpperCase()) {
            case CRITICAL -> "停机";
            case MAJOR -> "告警";
            case MINOR -> "预警";
            default -> "告警";
        };
    }

    /* ==================== 判定 ==================== */

    /**
     * 按规则判一个值命中哪一档。
     * <p>
     * 逐档比较后取<b>等级最高</b>的命中档——这就是「配一次只报最严重的那档」。
     *
     * @return 命中的档；一档都没命中返回 null
     */
    public static TierHit resolve(AlarmRule rule, double value) {
        if (rule == null) {
            return null;
        }
        List<AlarmRule.Tier> tiers = rule.getTiers();
        if (tiers.isEmpty()) {
            // 老规则：隐式单档，判定逻辑和升级前一字不差
            Double th = rule.getThreshold();
            if (th == null) {
                return null;
            }
            return compare(value, rule.getOp(), th)
                    ? new TierHit(rule.getLevel(), th)
                    : null;
        }
        TierHit best = null;
        for (AlarmRule.Tier t : tiers) {
            if (t == null || t.getThreshold() == null) {
                continue;
            }
            if (!compare(value, rule.getOp(), t.getThreshold())) {
                continue;
            }
            // 等级更高才替换：多档同时命中时留最严重的那个
            if (best == null || rank(t.getLevel()) > rank(best.level())) {
                best = new TierHit(t.getLevel(), t.getThreshold());
            }
        }
        return best;
    }

    /** 比较符判定。op 为空按 {@code >} 处理（和引擎里原来的行为一致） */
    public static boolean compare(double v, String op, double t) {
        return switch (op == null ? ">" : op.trim()) {
            case ">" -> v > t;
            case ">=" -> v >= t;
            case "<" -> v < t;
            case "<=" -> v <= t;
            case "==" -> Math.abs(v - t) < 1e-9;
            case "!=" -> Math.abs(v - t) >= 1e-9;
            default -> false;
        };
    }

    /* ==================== 换档 ==================== */

    /**
     * 就地换档：把命中的那一档写回这条告警。
     * <p>
     * <b>为什么必须就地改：</b>事件只有一条。同一台设备同一测点从预警升到停机，
     * 用户看到的还是一条告警，只是它变严重了。要是每次升档都新开一条，
     * 一台温度慢慢爬升的机器能刷出三条告警——那分级就白做了。
     * <p>
     * <b>为什么连实测值一起改：</b>等级、阈值、实测值、文案必须是同一轮判断的结果。
     * 只改等级不改值的话，列表里会出现「等级是停机、值却是刚才那一档的数」这种自相矛盾的行，
     * 事后对账时没人说得清这条告警到底是哪一刻触发的。
     *
     * @param hit     命中的档
     * @param value   这一轮量到的实测值
     * @param message 这一轮的文案
     * @return true = 升档（要发通知），false = 降档或没变（不发通知）
     */
    public static boolean applyTier(AlarmEvent ev, TierHit hit, Double value, String message) {
        if (ev == null || hit == null) {
            return false;
        }
        boolean up = rank(hit.level()) > rank(ev.getLevel());
        ev.setLevel(hit.level());
        ev.setThreshold(hit.threshold());
        if (value != null) {
            ev.setValue(value);
        }
        if (message != null && !message.isBlank()) {
            ev.setMessage(message);
        }
        return up;
    }

    /* ==================== 校验 ==================== */

    /**
     * 档位体检。返回人话，前端直接显示；没问题返回 null。
     * <p>
     * 查四件事：阈值有没有、等级合不合法、同档是不是重复了、阈值方向对不对。
     * 最后一条最要紧——阈值配反了不会「不报警」，会「报错的级别」，
     * 比如该停机的时候只报个预警，这种错比不报还危险，必须当场拒掉并说清哪两档反了。
     */
    public static String validateTiers(AlarmRule rule) {
        List<AlarmRule.Tier> tiers = rule.getTiers();
        if (tiers.isEmpty()) {
            return null;
        }
        String op = op(rule);
        Set<String> seen = new LinkedHashSet<>();
        for (AlarmRule.Tier t : tiers) {
            if (t == null) {
                return "档位配置为空";
            }
            String lv = normLevel(t.getLevel());
            if (!isLevel(lv)) {
                return "档位等级只能是 预警 / 告警 / 停机";
            }
            if (t.getThreshold() == null) {
                return "档位「" + levelText(lv) + "」缺少阈值";
            }
            if (!seen.add(lv)) {
                return "档位「" + levelText(lv) + "」重复了，同一个等级只能配一档";
            }
        }
        if (tiers.size() > 1) {
            if ("==".equals(op) || "!=".equals(op)) {
                return "等值型比较（" + op + "）不支持多档限值，只能配一档";
            }
            String bad = checkMonotonic(tiers, op);
            if (bad != null) {
                return bad;
            }
        }
        return null;
    }

    /**
     * 阈值单调性检查。
     * <p>
     * {@code >}/{@code >=} 时等级越高阈值应递增（预警 80 / 告警 85 / 停机 90）；
     * {@code <}/{@code <=} 时应递减（预警 10 / 告警 5 / 停机 0）。
     */
    private static String checkMonotonic(List<AlarmRule.Tier> tiers, String op) {
        List<AlarmRule.Tier> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparingInt(t -> rank(t.getLevel())));
        boolean up = ">".equals(op) || ">=".equals(op);
        for (int i = 1; i < sorted.size(); i++) {
            double lo = sorted.get(i - 1).getThreshold();
            double hi = sorted.get(i).getThreshold();
            if (up ? (hi > lo) : (hi < lo)) {
                continue;
            }
            return "档位阈值必须" + (up ? "递增" : "递减") + "：「" + levelText(sorted.get(i - 1).getLevel())
                    + "」" + op + " " + fmt(lo) + " 和「" + levelText(sorted.get(i).getLevel())
                    + "」" + op + " " + fmt(hi) + " 反了（等级越高阈值应"
                    + (up ? "越大" : "越小") + "）";
        }
        return null;
    }

    /* ==================== 展示 ==================== */

    /**
     * 档位的一句话描述：{@code 预警>80 / 告警>85 / 停机>90}。
     * 按等级从低到高排，不管用户是按什么顺序填的。
     */
    public static String describeTiers(AlarmRule rule) {
        if (rule == null) {
            return null;
        }
        List<AlarmRule.Tier> tiers = rule.getTiers();
        if (tiers.isEmpty()) {
            return null;
        }
        List<AlarmRule.Tier> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparingInt(t -> rank(t.getLevel())));
        StringBuilder sb = new StringBuilder();
        for (AlarmRule.Tier t : sorted) {
            if (t == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(levelText(t.getLevel())).append(op(rule))
                    .append(t.getThreshold() == null ? "--" : fmt(t.getThreshold()));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 老规则的单值描述：{@code >75}。分级规则返回 null */
    public static String describeSingle(AlarmRule rule) {
        if (rule == null || rule.isTiered() || rule.getThreshold() == null) {
            return null;
        }
        return op(rule) + fmt(rule.getThreshold());
    }

    /* ==================== 内部小工具 ==================== */

    private static String op(AlarmRule rule) {
        String op = rule.getOp();
        return op == null || op.isBlank() ? ">" : op.trim();
    }

    private static String normLevel(String level) {
        return level == null ? "" : level.trim().toUpperCase();
    }

    /** 整数就显示整数，别显示成 80.0 */
    static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 100d) / 100d);
    }
}
