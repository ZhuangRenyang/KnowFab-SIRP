package com.factory.hub.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    List<UserAccount> findAllByOrderByIdAsc();

    long countByRoleAndEnabled(String role, Boolean enabled);
}
