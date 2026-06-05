package com.amalitech.labresultsvalidator.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ModuleStatusConverter implements AttributeConverter<ModuleStatus, String> {

    @Override
    public String convertToDatabaseColumn(ModuleStatus attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.name().toLowerCase();
    }

    @Override
    public ModuleStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return ModuleStatus.valueOf(dbData.toUpperCase());
    }
}
