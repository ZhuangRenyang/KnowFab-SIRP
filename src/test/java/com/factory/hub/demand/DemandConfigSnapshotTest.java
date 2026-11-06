package com.factory.hub.demand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.factory.hub.config.AppSettingRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * 基本电费试算的**副作用防护**。
 * <p>
 * <b>守的是这个坑</b>：{@code DemandStore.current()} 返回的是缓存里的**同一个对象**。
 * 试算接口为了算「合同需量改成 400 会怎样」，会拿这个对象改字段——
 * 一改就等于把**全应用看到的配置**改了，而且没落库。
 * <p>
 * 后果：重启后配置变回来，但运行期间所有电费数字都是按试算参数算的。
 * <b>不报错、不白屏，只是数字悄悄变了</b>——这种 bug 最难发现，
 * 客户对账时才会发现"系统算的电费和我算的不一样"。
 * <p>
 * 所以试算必须走 {@code snapshot()}（深拷贝），本测试把这条钉死。
 */
class DemandConfigSnapshotTest {

    private static DemandStore newStore() {
        AppSettingRepository repo = mock(AppSettingRepository.class);
        when(repo.findById(any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new DemandStore(repo, new ObjectMapper());
    }

    @Test
    @DisplayName("current() 返回共享引用——这正是「不能拿它改数」的原因")
    void currentReturnsSharedReference() {
        DemandStore store = newStore();
        assertSame(store.current(), store.current(),
                "current() 返回缓存里的同一个对象；谁改它谁就改了全局配置");
    }

    @Test
    @DisplayName("snapshot() 返回独立副本：改副本不影响 current()")
    void snapshotIsIndependent() {
        DemandStore store = newStore();
        double before = store.current().getContractDemandKw();

        DemandConfig probe = store.snapshot();
        assertNotSame(store.current(), probe, "snapshot 必须是另一个对象");

        // 模拟试算：把五个数字全改一遍
        probe.setContractDemandKw(999);
        probe.setDemandPrice(99);
        probe.setTransformerKva(1);
        probe.setCapacityPrice(1);
        probe.setBillingMode(DemandConfig.MODE_CAPACITY);

        DemandConfig after = store.current();
        assertEquals(before, after.getContractDemandKw(),
                "试算改了合同需量，全局配置不能跟着变");
        assertEquals(38, after.getDemandPrice(), "试算改了需量单价，全局配置不能跟着变");
        assertEquals(800, after.getTransformerKva(), "试算改了变压器容量，全局配置不能跟着变");
        assertEquals(23, after.getCapacityPrice(), "试算改了容量单价，全局配置不能跟着变");
        assertEquals(DemandConfig.MODE_AUTO, after.getBillingMode(),
                "试算改了计费方式，全局配置不能跟着变");
    }

    @Test
    @DisplayName("两次 snapshot() 互不影响（不是复用同一份副本）")
    void snapshotsAreIndependentFromEachOther() {
        DemandStore store = newStore();
        DemandConfig a = store.snapshot();
        DemandConfig b = store.snapshot();
        a.setContractDemandKw(111);
        assertEquals(new DemandConfig().getContractDemandKw(), b.getContractDemandKw(),
                "改第一份副本不能影响第二份");
        assertTrue(a != b, "两次 snapshot 必须是两个对象");
    }
}
