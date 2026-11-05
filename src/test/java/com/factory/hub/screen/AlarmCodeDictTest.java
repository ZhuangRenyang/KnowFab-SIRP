package com.factory.hub.screen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报警码字典的基线回归（扩品牌之前先有的地基）。
 *
 * <p><b>为什么这个测试是扩品牌的前提</b>：
 * {@code AlarmCodeDict} 之前<b>零测试</b>，而它有 3 个消费方（大屏维修卡 / MES 开放接口 /
 * 维修工单历史样本查询），且字典是「改了立刻影响现场维修判断」的东西。
 * 先把存量行为钉死，后面加品牌才有「没弄坏」的判据。
 *
 * <p><b>三条纪律</b>：
 * <ol>
 *   <li><b>黑盒优先</b>：断言从「输入码 → 输出什么」反推，不绑私有实现</li>
 *   <li><b>存量零破坏</b>：三菱/台达原来能查的必须还能查，原来查不到的不能变成能查到
 *       （否则老客户看到数字突变 = 以为系统坏了）</li>
 *   <li><b>缺陷要会红</b>：已知缺陷（归一化碰撞）写成会失败的断言，
 *       修好后自动转绿——而不是写个永远绿的测试盖过去</li>
 * </ol>
 */
class AlarmCodeDictTest {

    private final AlarmCodeDict dict = new AlarmCodeDict(new ObjectMapper());

    /**
     * {@code load()} 是 {@code @PostConstruct} —— 只在 Spring 容器托管时自动调用。
     * 测试里手工 new 出来的实例字典是空的，必须显式加载，
     * 否则查什么都返回「未知」，测出来的绿是假的。
     */
    @BeforeEach
    void loadDictionary() {
        dict.load();
    }

    // ----------------------------------------------------------------
    // 一、存量行为基线：三菱 / 台达（扩品牌前就有 34 条，不能被弄坏）
    // ----------------------------------------------------------------

    @Test
    @DisplayName("三菱：存量码可查到，且 name/cause/remedy 三字段齐全")
    void mitsubishiExistingCodesStillResolve() {
        // 这几个码覆盖了存量字典里各类典型故障（过压/过流/过载/编码器/再生/通讯）
        String[][] cases = {
                {"10", "欠电压"},
                {"33", "过电压"},
                {"32", "过流"},
                {"50", "过载1"},
                {"16", "编码器异常1"},
                {"30", "再生异常"},
                {"8A", "串行通讯超时"},
        };
        for (String[] c : cases) {
            AlarmCodeDict.Resolved r = dict.lookup("mitsubishi", "alarm", c[0]);
            assertTrue(r.known(), "三菱码 " + c[0] + " 应该能查到");
            assertEquals(c[1], r.name(), "三菱码 " + c[0] + " 的名称变了");
            assertNotNull(r.cause(), "三菱码 " + c[0] + " 缺 cause");
            assertNotNull(r.remedy(), "三菱码 " + c[0] + " 缺 remedy");
            assertFalse(r.cause().isBlank(), "三菱码 " + c[0] + " 的 cause 是空的");
            assertFalse(r.remedy().isBlank(), "三菱码 " + c[0] + " 的 remedy 是空的");
        }
    }

    @Test
    @DisplayName("台达：存量码可查到（注意台达码是补零的三位格式）")
    void deltaExistingCodesStillResolve() {
        String[][] cases = {
                {"001", "过电流"},
                {"002", "过电压"},
                {"003", "欠电压"},
                {"006", "过负载"},
                {"028", "编码器内部错误"},
        };
        for (String[] c : cases) {
            AlarmCodeDict.Resolved r = dict.lookup("delta", "alarm", c[0]);
            assertTrue(r.known(), "台达码 " + c[0] + " 应该能查到");
            assertEquals(c[1], r.name(), "台达码 " + c[0] + " 的名称变了");
            assertNotNull(r.cause(), "台达码 " + c[0] + " 缺 cause");
            assertNotNull(r.remedy(), "台达码 " + c[0] + " 缺 remedy");
        }
    }

    @Test
    @DisplayName("存量边界：没配品牌 / 品牌不存在 / 码不存在 → 只显示原始码，不得编造含义")
    void unknownCasesNeverInventMeaning() {
        // 未指定品牌：品牌可为空
        AlarmCodeDict.Resolved noBrand = dict.lookup(null, "alarm", "33");
        assertFalse(noBrand.known(), "未指定品牌时不应声称已知");
        assertNull(noBrand.name(), "未指定品牌时不应给出名称");
        assertEquals("33", noBrand.code(), "未指定品牌时仍要回传原始码");

        // 品牌不在字典里（plc 是「自定义报警字」，本来就不该有内置表）
        AlarmCodeDict.Resolved plc = dict.lookup("plc", "alarm", "33");
        assertFalse(plc.known(), "PLC 自定义报警字不应声称已知");

        // 品牌存在但码不存在
        AlarmCodeDict.Resolved noCode = dict.lookup("mitsubishi", "alarm", "99999");
        assertFalse(noCode.known(), "字典里没有的码不应声称已知");
        assertNull(noCode.name(), "字典里没有的码不应给出名称");
    }

    @Test
    @DisplayName("存量行为：带前缀的码与裸码等价（AL.33 == 33）")
    void prefixNormalizationIsBackwardsCompatible() {
        AlarmCodeDict.Resolved bare = dict.lookup("mitsubishi", "alarm", "33");
        AlarmCodeDict.Resolved prefixed = dict.lookup("mitsubishi", "alarm", "AL.33");
        assertTrue(bare.known() && prefixed.known(), "两种写法都应能查到");
        assertEquals(bare.code(), prefixed.code(), "带前缀与不带前缀应归一到同一个码");
        assertEquals(bare.name(), prefixed.name(), "带前缀与不带前缀应查到同一个名称");
    }

    @Test
    @DisplayName("存量行为：大小写与空格不敏感")
    void lookupIsCaseAndSpaceInsensitive() {
        assertEquals(dict.lookup("mitsubishi", "alarm", "33").name(),
                dict.lookup("mitsubishi", "alarm", " 33 ").name());
        assertEquals(dict.lookup("mitsubishi", "alarm", "8a").name(),
                dict.lookup("mitsubishi", "alarm", "8A").name());
    }

    // ----------------------------------------------------------------
    // 二、字典文件自身的一致性（机械可验，不靠人眼）
    // ----------------------------------------------------------------

    @Test
    @DisplayName("index.txt 清单与实际文件一一对应（清单漏了文件 = 字典白做）")
    void indexTxtMatchesActualFiles() throws Exception {
        List<String> listed = readIndex();
        assertFalse(listed.isEmpty(), "index.txt 不应为空");

        for (String f : listed) {
            assertTrue(resourceExists("alarmcodes/" + f),
                    "index.txt 列了 " + f + "，但 resources 里没有这个文件 —— 该品牌等于没加载");
        }

        // 反向：resources 下有 json 但清单里没列 → 永远不会被加载（静默失效）
        for (String f : listJsonFiles()) {
            assertTrue(listed.contains(f),
                    "resources/alarmcodes/" + f + " 没在 index.txt 里登记 —— 加载时会被跳过");
        }
    }

    @Test
    @DisplayName("每个字典文件：schema 一致 + 字段齐全 + 码不重复")
    void everyDictionaryFileIsSchemaValid() throws Exception {
        for (String f : readIndex()) {
            Map<String, Object> node = readJson("alarmcodes/" + f);

            String brand = str(node.get("brand"));
            assertNotNull(brand, f + " 缺 brand");
            assertFalse(brand.isBlank(), f + " 的 brand 是空的");
            assertNotNull(node.get("brandText"), f + " 缺 brandText");
            assertNotNull(node.get("note"), f + " 缺 note");

            Object codesObj = node.get("codes");
            assertTrue(codesObj instanceof Map, f + " 的 codes 必须是对象");

            @SuppressWarnings("unchecked")
            Map<String, Object> codes = (Map<String, Object>) codesObj;
            assertFalse(codes.isEmpty(), f + " 的 codes 是空的（空字典等于没做）");

            for (Map.Entry<String, Object> e : codes.entrySet()) {
                String code = e.getKey();
                assertNotNull(code, f + " 有空的码键");
                assertFalse(code.isBlank(), f + " 有空白的码键");
                assertTrue(code.equals(code.trim()),
                        f + " 的码键 '" + code + "' 前后有空格（会永远查不到）");

                assertTrue(e.getValue() instanceof Map, f + " 的码 " + code + " 必须是对象");
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) e.getValue();

                for (String field : new String[]{"name", "cause", "remedy"}) {
                    Object v = item.get(field);
                    assertNotNull(v, f + " 的码 " + code + " 缺 " + field);
                    assertFalse(String.valueOf(v).isBlank(),
                            f + " 的码 " + code + " 的 " + field + " 是空字符串");
                }
            }
        }
    }

    @Test
    @DisplayName("码键的归一化形态唯一：两个不同码键不能归一到同一个（否则一条永远查不到）")
    void normalizedCodeKeysAreUnique() throws Exception {
        // 这条是本轮扩品牌最核心的机械门禁。
        // 归一化会把 AL./Er. 前缀剥掉，若同一品牌内有两个不同码键归一到同一个值，
        // 那么 lookup 永远只能命中其中一条，另一条形同虚设。
        for (String f : readIndex()) {
            Map<String, Object> node = readJson("alarmcodes/" + f);
            @SuppressWarnings("unchecked")
            Map<String, Object> codes = (Map<String, Object>) node.get("codes");

            Map<String, String> seen = new LinkedHashMap<>();
            for (String code : codes.keySet()) {
                String norm = AlarmCodeDict.normalize(code);
                String prev = seen.put(norm, code);
                assertNull(prev, f + " 里 '" + prev + "' 与 '" + code
                        + " 归一化后都是 '" + norm + "' —— 其中一条永远查不到");
            }
        }
    }

    @Test
    @DisplayName("别名索引不得让两个原始键指向同一条含义（G5 的字典侧）")
    void aliasIndexMustNotMergeDistinctKeys() throws Exception {
        // 台达的 "001"（过电流）与 "011"（编码器异常）去零后是 "1" 与 "11"，
        // 各自独立。若某品牌存在 "010" 与 "10"，去零别名会让 "10" 被抢占。
        for (String f : readIndex()) {
            Map<String, Object> node = readJson("alarmcodes/" + f);
            @SuppressWarnings("unchecked")
            Map<String, Object> codes = (Map<String, Object>) node.get("codes");

            Map<String, Object> alias = AlarmCodeDict.buildAliases(codes);
            for (String key : codes.keySet()) {
                Object viaAlias = alias.get(key);
                Object viaOriginal = codes.get(key);
                assertSame(viaOriginal, viaAlias,
                        f + " 里 '" + key + "' 通过别名索引拿到的不是它自己 —— 别名抢占了原始键");
            }
        }
    }

    @Test
    @DisplayName("字典加载成功：品牌数与清单一致（index.txt 有但解析失败会被静默跳过）")
    void allListedBrandsActuallyLoad() throws Exception {
        // AlarmCodeDict.load() 对单个文件解析失败只 log.warn 后继续 ——
        // 结果是「那个品牌永远查不到」，但系统看起来一切正常。
        List<String> listed = readIndex();
        List<String> loaded = new ArrayList<>();
        for (Map<String, Object> o : dict.brandOptions()) {
            loaded.add(String.valueOf(o.get("brand")));
        }
        for (String f : listed) {
            Object brand = readJsonQuiet("alarmcodes/" + f).get("brand");
            assertTrue(loaded.contains(String.valueOf(brand)),
                    "index.txt 列了 " + f + "（brand=" + brand + "），但加载后没有这个品牌");
        }
    }

    // ----------------------------------------------------------------
    // 三、别名索引（修台达 14 条生产不可达）
    //
    // 背景：设备报警字是数值型点位，读上来的是裸整数（trimNum → "1"），
    // 而台达手册的码带补零（AL.001）。归一化后 "1" ≠ "001" → 14 条永远查不到。
    // → 用「去前导零别名」把两条路都接上。
    //
    // 这三条断言是本轮修复的核心证据：
    //   ① 台达从「查不到」变成「查得到」
    //   ② 三菱行为零变化（不能为了修台达把三菱搞坏）
    //   ③ 别名不能抢占已有原始键（否则给现场一个错含义）
    // ----------------------------------------------------------------

    @Test
    @DisplayName("台达：裸整数能命中补零键（修复前 0 条可达，现在 14 条全部可达）")
    void deltaZeroPaddedCodesAreNowReachable() {
        // 这 14 条在修复前喂裸整数一条都查不到
        String[][] cases = {
                {"1", "过电流"}, {"2", "过电压"}, {"3", "欠电压"}, {"5", "回生异常"},
                {"6", "过负载"}, {"7", "过速度"}, {"8", "异常脉冲控制指令"}, {"9", "位置控制误差过大"},
                {"11", "编码器异常"}, {"12", "校正异常"}, {"13", "紧急停止"},
                {"15", "正向极限异常"}, {"17", "存储器异常"}, {"28", "编码器内部错误"},
        };
        for (String[] c : cases) {
            AlarmCodeDict.Resolved r = dict.lookup("delta", "alarm", c[0]);
            assertTrue(r.known(), "台达码 " + c[0] + " 应该能查到（补零别名）");
            assertEquals(c[1], r.name(), "台达码 " + c[0] + " 的名称不对");
        }
    }

    @Test
    @DisplayName("台达：补零写法本身也仍能查到（不能只修一边）")
    void deltaZeroPaddedFormStillResolves() {
        AlarmCodeDict.Resolved r = dict.lookup("delta", "alarm", "001");
        assertTrue(r.known(), "台达 AL.001 应该能查到");
        assertEquals("过电流", r.name());
    }

    @Test
    @DisplayName("三菱行为零变化（修台达不能动三菱）")
    void mitsubishiBehaviorUnchanged() {
        // 三菱键本来就是裸数字，别名对它必须是空操作
        String[] codes = {"10", "11", "12", "13", "16", "17", "20", "24", "25", "30",
                "31", "32", "33", "37", "45", "46", "50", "51", "52"};
        for (String c : codes) {
            AlarmCodeDict.Resolved r = dict.lookup("mitsubishi", "alarm", c);
            assertTrue(r.known(), "三菱码 " + c + " 应该能查到");
            assertEquals(c, r.code(), "三菱码 " + c + " 的 code 不该被别名改写");
        }
        // 8A 含字母：直接按字典键查能查到（它是文档码，现场可照手册核对）
        // 但裸整数通道喂不出它 —— 这一点由校验器的「可达性」检查守住，不在这里断言
        AlarmCodeDict.Resolved hex = dict.lookup("mitsubishi", "alarm", "8A");
        assertTrue(hex.known(), "8A 是三菱字典里的码，直接查应能查到");
        assertEquals("串行通讯超时", hex.name());
    }

    @Test
    @DisplayName("别名不能抢占已有原始键（G5：010 的别名不能指向 10 的含义）")
    void aliasMustNotShadowExistingKey() {
        Map<String, Object> codes = new LinkedHashMap<>();
        codes.put("010", Map.of("name", "补零那条"));
        codes.put("10", Map.of("name", "原始那条"));

        Map<String, Object> alias = AlarmCodeDict.buildAliases(codes);

        // "10" 必须是它自己，绝不能被 "010" 的别名抢占
        Object hit10 = alias.get("10");
        assertTrue(hit10 instanceof Map, "查 10 应该命中");
        assertEquals("原始那条", ((Map<?, ?>) hit10).get("name"),
                "查 10 命中了错误的含义 —— 别名抢占了已有原始键");
        // "010" 仍然命中自己
        assertEquals("补零那条", ((Map<?, ?>) alias.get("010")).get("name"));
    }

    @Test
    @DisplayName("别名索引：原始键优先、别名只补不覆盖（两遍扫描契约）")
    void aliasIndexContract() {
        Map<String, Object> codes = new LinkedHashMap<>();
        codes.put("001", Map.of("name", "过电流"));
        codes.put("011", Map.of("name", "编码器异常"));

        Map<String, Object> alias = AlarmCodeDict.buildAliases(codes);

        // 原始键都在
        assertNotNull(alias.get("001"));
        assertNotNull(alias.get("011"));
        // 去零别名也在（001 不是本品牌已有 key，可以注册）
        assertNotNull(alias.get("1"), "001 的去零别名 1 应该注册");
        assertNotNull(alias.get("11"), "011 的去零别名 11 应该注册");
        // 别名指向的必须是同一条含义
        assertEquals("过电流", ((Map<?, ?>) alias.get("1")).get("name"));
    }

    @Test
    @DisplayName("aliases[]：条目自带的别名可查，且不覆盖任何主键")
    void entryAliasesAreIndexed() {
        Map<String, Object> codes = new LinkedHashMap<>();
        codes.put("1824", Map.of(
                "displayCode", "A.720",
                "aliases", List.of("A720", "720"),
                "name", "过载（连续最大负载）"));
        codes.put("80", Map.of("name", "组合错误"));

        Map<String, Object> alias = AlarmCodeDict.buildAliases(codes);

        // 条目自带别名都能查到，且指向同一条含义
        assertEquals("过载（连续最大负载）", ((Map<?, ?>) alias.get("A720")).get("name"));
        assertEquals("过载（连续最大负载）", ((Map<?, ?>) alias.get("720")).get("name"));
        // 主键不被别名覆盖
        assertEquals("组合错误", ((Map<?, ?>) alias.get("80")).get("name"));
        assertSame(codes.get("80"), alias.get("80"), "别名不得抢占已有主键");
    }

    @Test
    @DisplayName("aliases[]：别名与已有主键冲突时不得覆盖（否则给现场错含义）")
    void entryAliasMustNotShadowPrimaryKey() {
        Map<String, Object> codes = new LinkedHashMap<>();
        // 「720」这条自己带别名 "80"，而 "80" 是另一条主键 —— 别名必须让位
        codes.put("1824", Map.of("aliases", List.of("80"), "name", "过载（连续最大负载）"));
        codes.put("80", Map.of("name", "组合错误"));

        Map<String, Object> alias = AlarmCodeDict.buildAliases(codes);

        assertEquals("组合错误", ((Map<?, ?>) alias.get("80")).get("name"),
                "别名抢占了已有主键 —— 查 80 会命中别的含义");
    }

    // ----------------------------------------------------------------
    // 四、displayCode：维修工看到的是厂家原始形态，不是裸数字
    // ----------------------------------------------------------------

    @Test
    @DisplayName("displayCode：命中时透出厂家原始码，没有则为 null")
    void displayCodeIsPassedThrough() {
        // 存量两个品牌现在也补了 displayCode（2026-11-05 按官方手册补齐厂家原始码形态），
        // 但它只是**展示字段**：不得影响 code()、不得影响命中、也不得出现 "undefined" 这种字面量。
        AlarmCodeDict.Resolved legacy = dict.lookup("mitsubishi", "alarm", "33");
        assertTrue(legacy.known());
        assertEquals("AL.33", legacy.displayCode(), "三菱 33 的 displayCode 应是厂家原始形态 AL.33");
        assertEquals("33", legacy.code(), "补 displayCode 不得改变归一化码");
        assertEquals("过电压", legacy.name());

        // 未收录的码同样为 null
        AlarmCodeDict.Resolved unknown = dict.lookup("mitsubishi", "alarm", "99999");
        assertFalse(unknown.known());
        assertNull(unknown.displayCode(), "未收录的码不应有 displayCode");
    }

    // ----------------------------------------------------------------
    // 工具方法
    // ----------------------------------------------------------------

    private static List<String> readIndex() throws Exception {
        List<String> out = new ArrayList<>();
        String idx = new String(resourceBytes("alarmcodes/index.txt"), StandardCharsets.UTF_8);
        for (String line : idx.split("\\r?\\n")) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<String> listJsonFiles() throws Exception {
        // 运行时目录与 jar 内不同，这里直接按已知资源清单探测
        List<String> out = new ArrayList<>();
        java.io.File dir = new java.io.File("src/main/resources/alarmcodes");
        java.io.File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) {
            for (java.io.File f : fs) {
                out.add(f.getName());
            }
        }
        return out;
    }

    private static boolean resourceExists(String path) {
        return AlarmCodeDictTest.class.getClassLoader().getResource(path) != null;
    }

    private static byte[] resourceBytes(String path) throws Exception {
        try (InputStream in = AlarmCodeDictTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "资源不存在：" + path);
            return in.readAllBytes();
        }
    }

    private static Map<String, Object> readJson(String path) throws Exception {
        return new ObjectMapper().readValue(resourceBytes(path), Map.class);
    }

    private static Map<String, Object> readJsonQuiet(String path) {
        try {
            return readJson(path);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
