package com.factory.hub.repo;

import com.factory.hub.model.DeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<DeviceEntity, Long> {

    Optional<DeviceEntity> findByCode(String code);

    boolean existsByCode(String code);

    List<DeviceEntity> findByEnabledTrue();

    List<DeviceEntity> findAllByOrderByIdAsc();
}
