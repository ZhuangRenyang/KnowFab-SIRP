package com.factory.hub.erp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ErpProdLineRepository extends JpaRepository<ErpProdLine, Long> {

    Optional<ErpProdLine> findByCode(String code);

    boolean existsByCode(String code);

    List<ErpProdLine> findAllByOrderBySortOrderAscCodeAsc();
}
