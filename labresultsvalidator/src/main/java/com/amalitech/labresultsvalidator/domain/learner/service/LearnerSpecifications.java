package com.amalitech.labresultsvalidator.domain.learner.service;

import com.amalitech.labresultsvalidator.domain.enums.LearnerStatus;
import com.amalitech.labresultsvalidator.domain.learner.entity.Learner;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class LearnerSpecifications {

    private LearnerSpecifications() {
    }

    static Specification<Learner> withFilters(
            UUID cohortId,
            UUID specializationId,
            LearnerStatus status,
            String search) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (cohortId != null) {
                predicates.add(cb.equal(root.get("cohort").get("id"), cohortId));
            }
            if (specializationId != null) {
                predicates.add(cb.equal(root.get("specialization").get("id"), specializationId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("fullName")), pattern),
                    cb.like(cb.lower(root.get("email")), pattern)
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
