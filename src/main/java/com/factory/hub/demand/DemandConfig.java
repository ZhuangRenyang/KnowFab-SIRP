package com.factory.hub.demand;

import java.util.ArrayList;
import java.util.List;

/**
 * 基本电费参数。
 * <p>
 * 国内大工业电费 = 电度电费（用了多少度）+ 基本电费（占了多少容量/需量）+ 力调电费。
 * 前面那套分时电价只管电度电费，这个类管基本电费——也就是客户每月固定要交的那笔。
 * <p>
 * 供电局给两种计费方式，企业可以选，一般是每年申报一次：
 * <ul>
 *   <li><b>按需量</b>：按当月最大 15 分钟平均功率（kW）收，用得省就交得少</li>
 *   <li><b>按容量</b>：按变压器容量（kVA）收，不管用不用都交这么多</li>
 * </ul>
 * 两条都算出来取小的（{@link #MODE_AUTO}），是现场最常用的策略：
 * 生产稳定、负荷高的厂按容量划算；负荷波动大的按需量划算。
 */
public class DemandConfig {

    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_DEMAND = "DEMAND";
    public static final String MODE_CAPACITY = "CAPACITY";

    /** 合同（申报）需量，kW。超过这个数要么被罚，要么下月被强制上调 */
    private double contractDemandKw = 500;

    /** 需量电价，元/kW·月 */
    private double demandPrice = 38;

    /** 变压器容量，kVA */
    private double transformerKva = 800;

    /** 容量电价，元/kVA·月 */
    private double capacityPrice = 23;

    /** AUTO = 两种都算取小的 */
    private String billingMode = MODE_AUTO;

    /** 到合同需量的这个比例就提醒，0.9 表示 90% */
    private double warnRatio = 0.9;

    /** 关掉就只显示不预警 */
    private boolean enabled = true;

    /** 参数体检，返回人话 */
    public List<String> validate() {
        List<String> out = new ArrayList<>();
        if (contractDemandKw <= 0) {
            out.add("合同需量要填大于 0 的数，这个数在供电合同上，照抄就行");
        }
        if (demandPrice <= 0) {
            out.add("需量电价没填，基本电费会算成 0");
        }
        if (MODE_CAPACITY.equals(billingMode) || MODE_AUTO.equals(billingMode)) {
            if (transformerKva <= 0) {
                out.add("按容量计费要填变压器容量（看铭牌 kVA），否则这条算不出来");
            }
            if (capacityPrice <= 0) {
                out.add("容量电价没填，按容量计费会算成 0");
            }
        }
        if (warnRatio <= 0 || warnRatio > 1.2) {
            out.add("预警比例建议 0.8 ~ 1.0 之间");
        }
        return out;
    }

    /** 算出来的月基本电费，元 */
    public double demandCost(double maxDemandKw) {
        return Math.max(0, maxDemandKw) * demandPrice;
    }

    public double capacityCost() {
        return Math.max(0, transformerKva) * capacityPrice;
    }

    /** 实际采用哪种计费方式（AUTO 时取便宜的那个） */
    public String effectiveMode(double maxDemandKw) {
        if (MODE_DEMAND.equals(billingMode) || MODE_CAPACITY.equals(billingMode)) {
            return billingMode;
        }
        return demandCost(maxDemandKw) <= capacityCost() ? MODE_DEMAND : MODE_CAPACITY;
    }

    public double monthlyCost(double maxDemandKw) {
        return MODE_CAPACITY.equals(effectiveMode(maxDemandKw))
                ? capacityCost()
                : demandCost(maxDemandKw);
    }

    public double getContractDemandKw() {
        return contractDemandKw;
    }

    public void setContractDemandKw(double contractDemandKw) {
        this.contractDemandKw = contractDemandKw;
    }

    public double getDemandPrice() {
        return demandPrice;
    }

    public void setDemandPrice(double demandPrice) {
        this.demandPrice = demandPrice;
    }

    public double getTransformerKva() {
        return transformerKva;
    }

    public void setTransformerKva(double transformerKva) {
        this.transformerKva = transformerKva;
    }

    public double getCapacityPrice() {
        return capacityPrice;
    }

    public void setCapacityPrice(double capacityPrice) {
        this.capacityPrice = capacityPrice;
    }

    public String getBillingMode() {
        return billingMode;
    }

    public void setBillingMode(String billingMode) {
        this.billingMode = billingMode;
    }

    public double getWarnRatio() {
        return warnRatio;
    }

    public void setWarnRatio(double warnRatio) {
        this.warnRatio = warnRatio;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
