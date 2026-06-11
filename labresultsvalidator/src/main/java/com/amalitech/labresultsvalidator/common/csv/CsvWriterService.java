package com.amalitech.labresultsvalidator.common.csv;

import com.opencsv.CSVWriter;
import com.opencsv.bean.CsvBindByName;
import com.opencsv.bean.HeaderColumnNameMappingStrategy;
import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.bean.StatefulBeanToCsvBuilder;
import com.opencsv.exceptions.CsvDataTypeMismatchException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Generic CSV writer built on OpenCSV, shared by features that need to emit a scoped upload
 * template (header only) or export beans (e.g. a corrections-only / rejected-rows file).
 *
 * <p>Columns are emitted in the order their fields are <em>declared</em> on the bean — not OpenCSV's
 * default alphabetical-by-header ordering — so the column order is controlled simply by the field
 * order in the {@code @CsvBindByName}-annotated DTO. Bean types must expose a public no-argument
 * constructor.
 */
@Service
public class CsvWriterService {

    /**
     * Write a header-only CSV template for the given bean type, with columns in field-declaration
     * order.
     *
     * @param out  destination writer (caller owns closing it)
     * @param type the OpenCSV-annotated bean type
     * @param <T>  the bean type
     */
    public <T> void writeTemplate(Writer out, Class<T> type) {
        try {
            CSVWriter csvWriter = new CSVWriter(out);
            csvWriter.writeNext(columnNames(type).toArray(new String[0]));
            csvWriter.flush();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to generate CSV template for " + type.getSimpleName(), e);
        }
    }

    /**
     * Write the given beans to CSV, including a header row, with columns in field-declaration order.
     *
     * @param out  destination writer (caller owns closing it)
     * @param rows the beans to write
     * @param type the OpenCSV-annotated bean type
     * @param <T>  the bean type
     */
    public <T> void write(Writer out, List<T> rows, Class<T> type) {
        HeaderColumnNameMappingStrategy<T> strategy = new HeaderColumnNameMappingStrategy<>();
        strategy.setType(type);
        strategy.setColumnOrderOnWrite(declarationOrder(type));
        try {
            StatefulBeanToCsv<T> beanToCsv = new StatefulBeanToCsvBuilder<T>(out)
                    .withMappingStrategy(strategy)
                    .build();
            beanToCsv.write(rows);
            out.flush();
        } catch (CsvDataTypeMismatchException | CsvRequiredFieldEmptyException | IOException e) {
            throw new IllegalStateException(
                    "Failed to write CSV for " + type.getSimpleName(), e);
        }
    }

    /** The {@code @CsvBindByName} column names of a bean type, in field-declaration order. */
    private static List<String> columnNames(Class<?> type) {
        List<String> columns = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            CsvBindByName binding = field.getAnnotation(CsvBindByName.class);
            if (binding == null) {
                continue;
            }
            columns.add(binding.column().isBlank() ? field.getName() : binding.column());
        }
        return columns;
    }

    /**
     * A comparator ordering header names by their field-declaration index, so OpenCSV writes data
     * columns in the same order the template advertises. Unknown columns sort last.
     */
    private static Comparator<String> declarationOrder(Class<?> type) {
        List<String> order = columnNames(type).stream()
                .map(column -> column.toUpperCase(Locale.ROOT))
                .toList();
        return Comparator.comparingInt(column -> {
            int index = order.indexOf(column.toUpperCase(Locale.ROOT));
            return index < 0 ? Integer.MAX_VALUE : index;
        });
    }
}
