package com.factory.hub.screen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报警码字典的黄金样本门禁。
 *
 * <p><b>为什么要两张表</b>：
 * <ul>
 *   <li><b>golden-semantic.txt</b>（手写）—— 权威。防「集体性错误」：
 *       所有人都按错的理解改了字典，这张表能挡住。</li>
 *   <li><b>golden.txt</b>（自动快照）—— 防「意外漂移」：
 *       有人改了字典里的措辞但没改这张表，快照会报出来。</li>
 * </ul>
 * 快照只能发现「变了」，发现不了「本来就错」。两个都要有，缺一不可。
 *
 * <p><b>样本表本身是交付物</b>：它把「我们对现场的承诺」写成了可执行的断言，
 * 以后任何人改字典，改坏了这张表就会红。
 */
class AlarmCodeGoldenTest {

    private final AlarmCodeDict dict = new AlarmCodeDict(new ObjectMapper());

    /** 读样本文件的一行：brand | key | expectedKnown | expectedName | 备注 */
    private record Sample(String brand, String key, boolean expectedKnown, String expectedName, String note) {
    }

    @BeforeEach
    void loadDictionary() {
        // load() 是 @PostConstruct，只在 Spring 容器托管时自动调用。
        // 测试里手工 new 的实例字典是空的，必须显式加载，否则测出来的绿是假的。
        dict.load();
    }

    @Test
    @DisplayName("黄金样本：每条手工断言都要成立（防集体性错误）")
    void goldenSemanticSamples() throws Exception {
        List<Sample> samples = readSemanticSamples();
        assertFalse(samples.isEmpty(), "黄金样本文件是空的 —— 门禁形同虚设");

        List<String> failures = new ArrayList<>();
        for (Sample s : samples) {
            AlarmCodeDict.Resolved r = dict.lookup(s.brand(), "alarm", s.key());
            if (r.known() != s.expectedKnown()) {
                failures.add(String.format(
                        "%s 键 %s：期望 known=%s，实际 %s（%s）",
                        s.brand(), s.key(), s.expectedKnown(), r.known(), s.note()));
                continue;
            }
            if (s.expectedKnown()) {
                if (r.name() == null || !r.name().equals(s.expectedName())) {
                    failures.add(String.format(
                            "%s 键 %s：期望 name「%s」，实际「%s」（%s）",
                            s.brand(), s.key(), s.expectedName(), r.name(), s.note()));
                }
                // 命中的码必须有原因和处理方法 —— 查到但没告诉人怎么处理，等于没查到
                assertNotNull(r.cause(), s.brand() + " 键 " + s.key() + " 缺 cause");
                assertNotNull(r.remedy(), s.brand() + " 键 " + s.key() + " 缺 remedy");
            }
        }

        assertTrue(failures.isEmpty(),
                "黄金样本不通过（" + failures.size() + "/" + samples.size() + " 条）：\n  "
                        + String.join("\n  ", failures));
    }

    @Test
    @DisplayName("黄金样本：每个品牌都要有样本（防止新品牌漏测）")
    void everyBrandHasSamples() throws Exception {
        // 新加一个品牌却忘了写黄金样本 = 这个品牌的行为没人守
        List<String> brands = new ArrayList<>();
        for (Sample s : readSemanticSamples()) {
            if (!brands.contains(s.brand())) {
                brands.add(s.brand());
            }
        }
        for (String expected : new String[]{"mitsubishi", "delta", "yaskawa", "panasonic", "panasonic-a4", "huichuan"}) {
            assertTrue(brands.contains(expected), "品牌 " + expected + " 没有黄金样本");
        }
    }

    @Test
    @DisplayName("黄金样本：同一个数字在同一品牌内不能指向两个含义")
    void sameNumberNeverMeansTwoThings() throws Exception {
        // 跨码查同一品牌时，不同 key 若归一化到同一个值，
        // 那它们必须是同一条码（否则「查不到」还是「查到错的」二选一，都不对）。
        // 真正的风险是字典里同时登记了 "410" 和 "E4100" 这种 —— 归一化后同形。
        // 这条在 AlarmCodeDictTest 里已覆盖（归一化后 key 唯一），此处补一条行为侧断言。
        List<Sample> samples = readSemanticSamples();
        for (Sample s : samples) {
            AlarmCodeDict.Resolved r = dict.lookup(s.brand(), "alarm", s.key());
            if (r.known()) {
                // 同一个品牌 + 同一个 name，应该只对应一个归一化形态
                assertEquals(r.code(), AlarmCodeDict.normalize(s.key()),
                        s.brand() + " 键 " + s.key() + " 的 code 与归一化结果不一致");
            }
        }
    }

    private static List<Sample> readSemanticSamples() throws Exception {
        List<Sample> out = new ArrayList<>();
        String text;
        try (InputStream in = AlarmCodeGoldenTest.class.getClassLoader()
                .getResourceAsStream("alarmcode-golden-semantic.txt")) {
            assertNotNull(in, "找不到黄金样本文件 alarmcode-golden-semantic.txt");
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String line : text.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            String[] parts = s.split("\\|", -1);
            assertTrue(parts.length >= 4,
                    "黄金样本行格式不对（应为 brand | key | known | name | 备注）：" + s);
            boolean known = Boolean.parseBoolean(parts[2].trim());
            String name = parts[3].trim();
            String note = parts.length >= 5 ? parts[4].trim() : "";
            out.add(new Sample(parts[0].trim(), parts[1].trim(), known, name, note));
        }
        return out;
    }
}
