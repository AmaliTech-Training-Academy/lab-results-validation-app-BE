package com.amalitech.labresultsvalidator.domain.grading.sync;

import org.apache.poi.ss.usermodel.Workbook;

import java.io.IOException;

/**
 * A changed workbook, downloaded and opened locally by POI, ready for the row-processing step
 * (B5–B9) to consume.
 *
 * <p>Carries the open {@link Workbook} because POI opens it during fetch — that is where a
 * corrupt file has to be caught (B4 AC2), so parsing a second time downstream would be waste.
 * Callers must use try-with-resources.
 *
 * <p>{@code content} is retained because the caller uploads these exact bytes to S3 <em>after</em>
 * processing, and that upload is what advances the change-detection baseline.
 *
 * @param fileName         workbook filename as it appears in SharePoint
 * @param sharepointItemId stable drive item id of the source file
 * @param s3Key            key these bytes will be archived under
 * @param content          the exact bytes downloaded and handed to POI
 * @param workbook         the open POI workbook
 */
public record FetchedWorkbook(
    String fileName,
    String sharepointItemId,
    String s3Key,
    byte[] content,
    Workbook workbook
) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        workbook.close();
    }
}
