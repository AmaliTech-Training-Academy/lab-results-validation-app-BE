package com.amalitech.labresultsvalidator.domain.csvUploads.repository;

import com.amalitech.labresultsvalidator.domain.csvUploads.entity.CsvUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link CsvUpload} audit records.
 */
@Repository
public interface CsvUploadRepository extends JpaRepository<CsvUpload, UUID> {

    /** Find a prior upload by its file digest, used to reject byte-identical re-uploads. */
    Optional<CsvUpload> findByFileSha256(String fileSha256);
}
