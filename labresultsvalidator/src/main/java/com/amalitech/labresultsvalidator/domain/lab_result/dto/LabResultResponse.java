package com.amalitech.labresultsvalidator.domain.lab_result.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Value
@Builder
public class LabResultResponse {
    UUID id;
    String learnerEmail;
    String learnerName;
    UUID labId;
    String labTitle;
    BigDecimal score;
    BigDecimal maxScoreSnapshot;
    short attemptNumber;
    LocalDate submittedOn;
    String gradedBy;
}
