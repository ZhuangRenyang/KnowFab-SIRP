package com.factory.hub.erp.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ErpBomRepository extends JpaRepository<ErpBom, Long> {

    /** 一个产品的完整用料清单（按材料 id 排序，保证界面顺序稳定） */
    List<ErpBom> findByProductSkuIdOrderByMaterialSkuIdAsc(Long productSkuId);

    Optional<ErpBom> findByProductSkuIdAndMaterialSkuId(Long productSkuId, Long materialSkuId);

    /** 这个材料被哪些产品用着——删 SKU 前要拦，否则 BOM 成孤儿 */
    List<ErpBom> findByMaterialSkuId(Long materialSkuId);

    long countByProductSkuId(Long productSkuId);

    void deleteByProductSkuId(Long productSkuId);
}
