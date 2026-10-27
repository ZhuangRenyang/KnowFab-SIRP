package com.factory.hub.erp;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErpSkuRepository extends JpaRepository<ErpSku, Long> {
    Optional<ErpSku> findByCodeIgnoreCase(String code);
}
