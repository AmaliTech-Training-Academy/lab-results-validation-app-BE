package com.amalitech.labresultsvalidator.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class UploadStatusConverter implements AttributeConverter<UploadStatus, String> {

    @Override
    public String convertToDatabaseColumn(UploadStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase();
    }

    @Override
    public UploadStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : UploadStatus.valueOf(dbData.toUpperCase());
    }
}