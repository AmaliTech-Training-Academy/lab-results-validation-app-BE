package com.amalitech.labresultsvalidator.domain.user.repository;

import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {
    Optional<User> findByEmail(String email);
    Optional<User> findById(UUID id);
    Optional<User> findByIdAndIsActiveTrue(UUID id);

    /** Admin-alert recipient resolution — deterministic pick if more than one active admin exists. */
    Optional<User> findFirstByRoleAndIsActiveTrueOrderByCreatedAtAsc(UserRole role);

    /**
     * Every admin who should receive a run digest (C4 AC1) or an immediate alert (C5 AC1). Those ACs
     * say "all active admins", so picking one deterministically is not enough for them.
     */
    List<User> findAllByRoleAndIsActiveTrue(UserRole role);
}
