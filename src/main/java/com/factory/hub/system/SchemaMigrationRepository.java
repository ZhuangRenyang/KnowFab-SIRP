package com.factory.hub.system;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SchemaMigrationRepository extends JpaRepository<SchemaMigration, String> {

    List<SchemaMigration> findAllByOrderByVersionDesc();
}
