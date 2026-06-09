package com.amalitech.labresultsvalidator.common.csv;

import com.opencsv.CSVWriter;
import com.opencsv.bean.HeaderColumnNameMappingStrategy;
import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.bean.StatefulBeanToCsvBuilder;
import com.opencsv.exceptions.CsvDataTypeMismatchException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Generic CSV writer built on OpenCSV, shared by features that need to emit a scoped upload
 * template (header only) or export beans (e.g. a corrections-only / rejected-rows file).
 *
 * <p>Column order follows OpenCSV's default for {@link HeaderColumnNameMappingStrategy}. Bean
 * types must expose a public no-argument constructor so a template header can be generated without
 * data.
 */
@Service
public class CsvWriterService {

    /**
     * Write a header-only CSV template for the given bean type.
     *
     * @param out  destination writer (caller owns closing it)
     * @param type the OpenCSV-annotated bean type
     * @param <T>  the bean type
     */
    public <T> void writeTemplate(Writer out, Class<T> type) {
        HeaderColumnNameMappingStrategy<T> strategy = new HeaderColumnNameMappingStrategy<>();
        strategy.setType(type);
        try {
            T blank = type.getDeclaredConstructor().newInstance();
            String[] header = strategy.generateHeader(blank);
            CSVWriter csvWriter = new CSVWriter(out);
            csvWriter.writeNext(header);
            csvWriter.flush();
        } catch (ReflectiveOperationException | CsvRequiredFieldEmptyException | IOException e) {
            throw new IllegalStateException(
                    "Unable to generate CSV template for " + type.getSimpleName(), e);
        }
    }

    /**
     * Write the given beans to CSV, including a header row.
     *
     * @param out  destination writer (caller owns closing it)
     * @param rows the beans to write
     * @param type the OpenCSV-annotated bean type
     * @param <T>  the bean type
     */
    public <T> void write(Writer out, List<T> rows, Class<T> type) {
        HeaderColumnNameMappingStrategy<T> strategy = new HeaderColumnNameMappingStrategy<>();
        strategy.setType(type);
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
}
