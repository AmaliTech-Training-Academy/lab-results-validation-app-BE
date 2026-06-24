package com.amalitech.labresultsvalidator.domain.csvUploads.repository;

import com.amalitech.labresultsvalidator.domain.csvUploads.dto.CsvUploadFilterRequest;
import com.amalitech.labresultsvalidator.domain.csvUploads.entity.CsvUpload;
import com.amalitech.labresultsvalidator.domain.enums.UploadStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CsvUploadSpecification {

    private CsvUploadSpecification() {}

    public static Specification<CsvUpload> withFilters(CsvUploadFilterRequest filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = buildCommonPredicates(filter, root, cb);

            String email = filter.getUploadedByEmail();
            if (email != null && !email.isBlank()) {
                predicates.add(cb.like(
                    cb.lower(root.get("uploadedByUser").get("email")),
                    "%" + email.toLowerCase() + "%"
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Same filters as {@link #withFilters} but always restricts to the given owner.
     * The {@code uploadedByEmail} field on the filter is ignored — ownership is enforced
     * by the {@code ownerId} argument.
     */
    public static Specification<CsvUpload> withFiltersForOwner(
            CsvUploadFilterRequest filter, UUID ownerId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = buildCommonPredicates(filter, root, cb);
            predicates.add(cb.equal(root.get("uploadedByUser").get("id"), ownerId));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static List<Predicate> buildCommonPredicates(
            CsvUploadFilterRequest filter, Root<CsvUpload> root, CriteriaBuilder cb) {
        List<Predicate> predicates = new ArrayList<>();

        OffsetDateTime startDate = filter.getStartDate();
        if (startDate != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("uploadedAt"), startDate));
        }

        OffsetDateTime endDate = filter.getEndDate();
        if (endDate != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("uploadedAt"), endDate));
        }

        UploadStatus status = filter.getStatus();
        if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
        }

        String search = filter.getSearch();
        if (search != null && !search.isBlank()) {
            List<Predicate> searchPredicates = new ArrayList<>();
            searchPredicates.add(cb.like(
                cb.lower(root.get("filename")),
                "%" + search.toLowerCase() + "%"
            ));
            try {
                UUID id = UUID.fromString(search.trim());
                searchPredicates.add(cb.equal(root.get("id"), id));
            } catch (IllegalArgumentException ignored) {
                // search term is not a UUID; only filename matching applies
            }
            predicates.add(cb.or(searchPredicates.toArray(new Predicate[0])));
        }

        return predicates;
    }
}
