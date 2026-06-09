package com.amalitech.labresultsvalidator.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Bridges the uppercase Java enum (ACTIVE, ARCHIVED)
 * and the lowercase PostgreSQL enum values ('active', 'archived').
 */
@Converter(autoApply = true)
public class LearnerStatusConverter implements AttributeConverter<LearnerStatus, String> {

    @Override
    public String convertToDatabaseColumn(LearnerStatus attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.name().toLowerCase();
    }

    @Override
    public LearnerStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return LearnerStatus.valueOf(dbData.toUpperCase());
    }
}
