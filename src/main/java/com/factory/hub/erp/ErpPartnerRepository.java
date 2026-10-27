package com.factory.hub.erp;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErpPartnerRepository extends JpaRepository<ErpPartner, Long> {
    Optional<ErpPartner> findByCodeIgnoreCase(String code);
}
