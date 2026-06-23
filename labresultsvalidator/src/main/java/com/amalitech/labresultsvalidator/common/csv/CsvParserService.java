package com.amalitech.labresultsvalidator.common.csv;

import com.opencsv.CSVReader;
import com.opencsv.bean.CsvBindByName;
import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;
import com.opencsv.bean.HeaderColumnNameMappingStrategy;
import com.opencsv.exceptions.CsvException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generic CSV reader built on OpenCSV that all bulk-upload features share.
 *
 * <p>A consumer declares a plain POJO whose fields are annotated with
 * {@link com.opencsv.bean.CsvBindByName} and calls {@link #parse(MultipartFile, Class)}. The
 * service rejects whole-file structural problems (empty/unreadable file, missing required header
 * columns, row count over {@link CsvConstants#MAX_ROWS}) with {@link MalformedCsvException}, and
 * otherwise returns a {@link CsvParseResult} with the rows that bound successfully plus a per-row
 * error list — the partial-success model used across the app.
 *
 * <p>Line numbers are physical, 1-based (the header is line 1, the first data row is line 2). The
 * mapping of valid rows to line numbers assumes one logical record per physical line and no blank
 * interior lines, which holds for the structured uploads this system accepts.
 */
@Service
public class CsvParserService {

    /**
     * Parse an uploaded CSV into beans of the given type.
     *
     * @param file the uploaded file
     * @param type the OpenCSV-annotated bean type to bind each row to
     * @param <T>  the bean type
     * @return the valid rows and per-row errors
     * @throws MalformedCsvException for whole-file structural failures
     */
    public <T> CsvParseResult<T> parse(MultipartFile file, Class<T> type) {
        validateFile(file);
        String content = readContent(file);

        String[] header;
        int dataRowCount;
        try (CSVReader probe = new CSVReader(new StringReader(content))) {
            List<String[]> records = probe.readAll();
            if (records.isEmpty()) {
                throw new MalformedCsvException("CSV file is empty.");
            }
            header = records.get(0);
            dataRowCount = records.size() - 1;
        } catch (IOException | CsvException e) {
            throw new MalformedCsvException("CSV file is malformed or unreadable.", e);
        }

        validateHeader(header, type);
        if (dataRowCount > CsvConstants.MAX_ROWS) {
            throw new MalformedCsvException(
                    "CSV exceeds the maximum of " + CsvConstants.MAX_ROWS + " rows.");
        }

        HeaderColumnNameMappingStrategy<T> strategy = new HeaderColumnNameMappingStrategy<>();
        strategy.setType(type);

        CsvToBean<T> csvToBean = new CsvToBeanBuilder<T>(new StringReader(content))
                .withMappingStrategy(strategy)
                .withThrowExceptions(false)
                .withIgnoreLeadingWhiteSpace(true)
                .build();

        List<T> beans = csvToBean.parse();
        List<CsvRowError> errors = csvToBean.getCapturedExceptions().stream()
                .map(this::toRowError)
                .toList();

        List<ParsedRow<T>> validRows = assignLineNumbers(beans, errors, dataRowCount);
        return new CsvParseResult<>(validRows, errors);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MalformedCsvException("CSV file is empty or missing.");
        }
        String contentType = file.getContentType();
        if (contentType != null
                && !CsvConstants.ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new MalformedCsvException("Unsupported content type: " + contentType);
        }
    }

    private String readContent(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MalformedCsvException("Unable to read the uploaded CSV file.", e);
        }
    }

    private void validateHeader(String[] header, Class<?> type) {
        Set<String> present = Arrays.stream(header)
                .filter(Objects::nonNull)
                .map(column -> column.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<String> missing = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            CsvBindByName binding = field.getAnnotation(CsvBindByName.class);
            if (binding == null || !binding.required()) {
                continue;
            }
            String column = binding.column().isBlank() ? field.getName() : binding.column();
            if (!present.contains(column.toUpperCase(Locale.ROOT))) {
                missing.add(column);
            }
        }

        if (!missing.isEmpty()) {
            throw new MalformedCsvException(
                    "CSV is missing required column(s): " + String.join(", ", missing));
        }
    }

    /**
     * Pair each successfully bound bean with its physical line number by walking the data lines in
     * order and skipping those that produced an error.
     */
    private <T> List<ParsedRow<T>> assignLineNumbers(
            List<T> beans, List<CsvRowError> errors, int dataRowCount) {
        Set<Long> errorLines = errors.stream()
                .map(CsvRowError::rowNumber)
                .collect(Collectors.toSet());

        List<ParsedRow<T>> validRows = new ArrayList<>(beans.size());
        Iterator<T> beanIterator = beans.iterator();
        for (long line = 2; line <= dataRowCount + 1 && beanIterator.hasNext(); line++) {
            if (errorLines.contains(line)) {
                continue;
            }
            validRows.add(new ParsedRow<>(line, beanIterator.next()));
        }
        return validRows;
    }

    private CsvRowError toRowError(CsvException exception) {
        if (exception instanceof CsvRequiredFieldEmptyException required) {
            java.lang.reflect.Field destField = required.getDestinationField();
            String column = null;
            if (destField != null) {
                CsvBindByName binding = destField.getAnnotation(CsvBindByName.class);
                column = (binding != null && !binding.column().isBlank())
                    ? binding.column().toUpperCase(Locale.ROOT)
                    : destField.getName().toUpperCase(Locale.ROOT);
            }
            return new CsvRowError(exception.getLineNumber(), column, "V3",
                column != null ? column + " is required" : exception.getLocalizedMessage());
        }
        return new CsvRowError(exception.getLineNumber(), null, exception.getLocalizedMessage());
    }
}
