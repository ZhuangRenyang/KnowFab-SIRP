package com.factory.hub.ai;

import com.factory.hub.auth.UserAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * AI 工具的**授权口径**契约测试（2026-11-07 补）。
 * <p>
 * 为什么补：{@link AiChatService} 1016 行、15 个工具、此前 **0 个测试**，
 * 而它手里握着**两个写操作**（建维修单 / 派工）。项目铁律是
 * 「AI 的写操作必须走角色校验并落审计（不得绕过 R 系列铁律）」——
 * 这条铁律的落地就是一个数据定义：每个工具的 {@code roles} 集合。
 * <p>
 * 为什么用反射读 {@code tools()} 而不是断言源码字符串：
 * 源码字符串断言会挂在措辞上（改个注释就飘），而这批数据是**运行时真正生效的那份**
 * ⇒ 钉数据才是钉行为。（同一条纪律：把注解/数据本身钉住，而不是钉它的文本形式。）
 * <p>
 * 钉的不变量：
 * <ul>
 *   <li>每个工具的 {@code roles} **非空** —— 空集等于「谁都能用」，是安全隐患</li>
 *   <li>每个工具都含 {@code ADMIN} —— 没有工具是「管理员也调不了」的孤儿</li>
 *   <li><b>两个写工具不含 {@code SALES}</b> —— 销售角色不该能建维修单/派工</li>
 *   <li>写工具的角色集合不得比 {@code SHOP_FLOOR} 更宽</li>
 * </ul>
 */
class AiChatServiceToolAuthTest {

    /** 写操作：会落库 + 写审计。这两个是「AI 能办事」的全部动作面 */
    private static final Set<String> WRITE_TOOLS = Set.of("create_maint_order", "assign_maint_order");

    /** 项目里的四个角色（见 {@link UserAccount}） */
    private static final Set<String> ALL_ROLES = Set.of(
            UserAccount.ROLE_ADMIN, UserAccount.ROLE_MANAGER,
            UserAccount.ROLE_LEADER, UserAccount.ROLE_SALES);

    /** 构造一个只有依赖壳的实例：19 个参数全 mock，构造器只做赋值 */
    private static AiChatService newService() throws Exception {
        Constructor<?> ctor = AiChatService.class.getDeclaredConstructors()[0];
        Class<?>[] types = ctor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            try {
                args[i] = mock(types[i]);
            } catch (Exception e) {
                args[i] = null;   // final 类 mock 不了（如部分 Jackson 类型）⇒ 构造器只赋值，给 null 无妨
            }
        }
        ctor.setAccessible(true);
        return (AiChatService) ctor.newInstance(args);
    }

    /** 反射读出 tools() 里的 (工具名 → 角色集合) */
    private static Map<String, Set<String>> toolRoles(AiChatService svc) throws Exception {
        Method tools = AiChatService.class.getDeclaredMethod("tools");
        tools.setAccessible(true);
        List<?> list = (List<?>) tools.invoke(svc);

        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Object t : list) {
            Method name = t.getClass().getDeclaredMethod("name");
            Method roles = t.getClass().getDeclaredMethod("roles");
            name.setAccessible(true);
            roles.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> rs = (Set<String>) roles.invoke(t);
            out.put((String) name.invoke(t), rs);
        }
        return out;
    }

    @Test
    @DisplayName("工具清单：15 个，每个都有**非空**角色集合（空集 = 谁都能用，是安全隐患）")
    void everyToolHasNonEmptyRoles() throws Exception {
        Map<String, Set<String>> roles = toolRoles(newService());

        assertFalse(roles.isEmpty(), "工具清单不能是空的");
        List<String> empty = new ArrayList<>();
        for (var e : roles.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) empty.add(e.getKey());
        }
        assertTrue(empty.isEmpty(),
                "这些工具的角色集合是空的（等于对所有角色开放）：" + empty
                        + " ⇒ 必须显式列出允许的角色");
    }

    @Test
    @DisplayName("每个工具都含 ADMIN（没有「管理员也调不了」的孤儿工具）")
    void everyToolAllowsAdmin() throws Exception {
        Map<String, Set<String>> roles = toolRoles(newService());

        List<String> noAdmin = new ArrayList<>();
        for (var e : roles.entrySet()) {
            if (!e.getValue().contains(UserAccount.ROLE_ADMIN)) noAdmin.add(e.getKey());
        }
        assertTrue(noAdmin.isEmpty(), "这些工具不允许 ADMIN 调用：" + noAdmin);
    }

    @Test
    @DisplayName("工具角色只能是已知的四个角色（拼错的角色名会把工具锁死）")
    void rolesAreKnownConstants() throws Exception {
        Map<String, Set<String>> roles = toolRoles(newService());

        List<String> unknown = new ArrayList<>();
        for (var e : roles.entrySet()) {
            for (String r : e.getValue()) {
                if (!ALL_ROLES.contains(r)) unknown.add(e.getKey() + "=" + r);
            }
        }
        assertTrue(unknown.isEmpty(),
                "出现了未知角色名（拼错会让该工具对所有人不可用）：" + unknown);
    }

    @Test
    @DisplayName("两个**写工具**：必须存在，且**不含 SALES**（销售不能建维修单/派工）")
    void writeToolsExcludeSalesRole() throws Exception {
        Map<String, Set<String>> roles = toolRoles(newService());

        for (String w : WRITE_TOOLS) {
            Set<String> rs = roles.get(w);
            assertTrue(rs != null, "写工具不见了：" + w + "（AI 的「能办事」全靠这两个）");
            // ⚠️ 这是本次最重要的一条：写操作会落库 + 写审计，
            //    若把 SALES 放进来，销售就能批量改维修工单状态。
            assertFalse(rs.contains(UserAccount.ROLE_SALES),
                    "写工具 " + w + " 不能给 SALES 角色（销售不该能改维修工单）");
            assertTrue(rs.contains(UserAccount.ROLE_MANAGER),
                    "写工具 " + w + " 应允许 MANAGER（车间主任才是派工的人）");
        }
    }

    @Test
    @DisplayName("query_stock 刻意对 SALES 开放（销售报价要查可承诺量）—— 不是误配置，别删")
    void stockQueryIsDeliberatelyOpenToSales() throws Exception {
        Set<String> rs = toolRoles(newService()).get("query_stock");

        assertTrue(rs != null && rs.contains(UserAccount.ROLE_SALES),
                "query_stock 必须对 SALES 开放：销售报价时要查「可承诺量（现存量-订单占用）」，"
                        + "这是全仓唯一给 SALES 的 AI 工具（其余 14 个都不含 SALES）"
                        + " ⇒ 若被当成「权限过宽」收紧，销售就没法用 AI 查货了");
    }

    @Test
    @DisplayName("写工具的角色集合不得比 SHOP_FLOOR 更宽（只能更严，不能更松）")
    void writeToolRolesAreNoWiderThanShopFloor() throws Exception {
        // SHOP_FLOOR = {ADMIN, MANAGER, LEADER}，见 AiChatService:132
        Set<String> shopFloor = Set.of(
                UserAccount.ROLE_ADMIN, UserAccount.ROLE_MANAGER, UserAccount.ROLE_LEADER);
        Map<String, Set<String>> roles = toolRoles(newService());

        for (String w : WRITE_TOOLS) {
            Set<String> rs = roles.get(w);
            assertTrue(rs != null, "写工具不见了：" + w);
            List<String> wider = new ArrayList<>();
            for (String r : rs) {
                if (!shopFloor.contains(r)) wider.add(r);
            }
            assertTrue(wider.isEmpty(),
                    "写工具 " + w + " 授权比 SHOP_FLOOR 更宽：" + wider
                            + " ⇒ 写操作只能收紧，不能放开");
        }
    }
}
