package com.amalitech.labresultsvalidator.domain.user.repository;

import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findById(UUID id);
    Optional<User> findByIdAndIsActiveTrue(UUID id);
    List<User> findAllByRole(UserRole role);
}
