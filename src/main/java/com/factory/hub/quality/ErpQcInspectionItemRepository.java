package com.factory.hub.quality;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ErpQcInspectionItemRepository extends JpaRepository<ErpQcInspectionItem, Long> {

    List<ErpQcInspectionItem> findByInspectionIdOrderByLineNoAsc(Long inspectionId);

    /** 改草稿时整表替换：先删旧行再插新行，行号不会累加出错 */
    int deleteByInspectionId(Long inspectionId);

    long countByDefectCodeId(Long defectCodeId);
}
