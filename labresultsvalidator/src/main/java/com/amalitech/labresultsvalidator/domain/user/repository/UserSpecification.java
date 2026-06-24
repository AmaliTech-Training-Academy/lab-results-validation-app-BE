package com.amalitech.labresultsvalidator.domain.user.repository;

import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.entity.UserModuleAssignment;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class UserSpecification {

    private UserSpecification() {}

    public static Specification<User> withFilters(String email, Boolean active, UUID moduleId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("role"), UserRole.INSTRUCTOR));

            if (email != null && !email.isBlank()) {
                predicates.add(cb.like(
                    cb.lower(root.get("email")),
                    "%" + email.toLowerCase() + "%"
                ));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("isActive"), active));
            }
            if (moduleId != null) {
                Subquery<UUID> sub = query.subquery(UUID.class);
                Root<UserModuleAssignment> uma = sub.from(UserModuleAssignment.class);
                sub.select(uma.get("user").get("id"))
                   .where(cb.equal(uma.get("module").get("id"), moduleId));
                predicates.add(root.get("id").in(sub));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
