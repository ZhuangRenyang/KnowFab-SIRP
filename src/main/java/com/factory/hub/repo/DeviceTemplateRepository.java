package com.factory.hub.repo;

import com.factory.hub.model.DeviceTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTemplateRepository extends JpaRepository<DeviceTemplateEntity, Long> {

    List<DeviceTemplateEntity> findAllByOrderByIdDesc();

    Optional<DeviceTemplateEntity> findByName(String name);

    boolean existsByName(String name);
}
