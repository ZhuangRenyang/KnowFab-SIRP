package com.factory.hub.erp;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErpWarehouseRepository extends JpaRepository<ErpWarehouse, Long> {
    Optional<ErpWarehouse> findByCodeIgnoreCase(String code);
    Optional<ErpWarehouse> findByDefaultWarehouseTrue();
    List<ErpWarehouse> findByDefaultWarehouseTrueAndIdNot(Long id);
}
