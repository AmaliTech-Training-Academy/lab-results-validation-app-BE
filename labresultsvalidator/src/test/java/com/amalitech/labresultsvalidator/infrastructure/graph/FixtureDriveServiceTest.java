package com.amalitech.labresultsvalidator.infrastructure.graph;

import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphItemTypeException;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphSiteViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link FixtureDriveService} honours the {@link GraphDriveService} contract — including
 * the refusals, not only the happy paths, because a fake that can only succeed cannot exercise
 * the error branches the gates are built around.
 *
 * <p>The change-detection tests are the important ones. Everything downstream (D3's hash
 * short-circuit, B3's unchanged-file skip) rests on the promise that the hash and version id move
 * when content moves and stay put when it does not.
 */
class FixtureDriveServiceTest {

    private static final String SITE_ID = "fixture-site";
    private static final String WEB_BASE = "https://fixtures.invalid/sites/validata";
    private static final long MAX_BYTES = 1024;

    @TempDir
    Path root;

    private FixtureDriveService service;
    private Path cohort;
    private Path scores;
    private Path workbook;

    @BeforeEach
    void setUp() throws IOException {
        // The layout the folder contract requires (§3.2), which is also what QA_Fixtures/ has.
        cohort = Files.createDirectory(root.resolve("Demo Happy Path Cohort"));
        Files.createDirectory(cohort.resolve("Reference Data"));
        scores = Files.createDirectory(cohort.resolve("Lab Scores"));
        workbook = Files.writeString(scores.resolve("Module 1 Grading.xlsx"), "workbook-v1");
        Files.writeString(scores.resolve(".hidden"), "ignored");
        Files.writeString(scores.resolve("~$Module 1 Grading.xlsx"), "lock file");

        service = newService(SITE_ID);
    }

    private FixtureDriveService newService(String sanctionedSiteId) {
        return new FixtureDriveService(
            new FixtureDriveProperties(root.toString(), SITE_ID, WEB_BASE),
            new AzureGraphProperties(null, null, null, sanctionedSiteId),
            new SharePointProperties(
                "Reference Data", "Lab Scores",
                new SharePointProperties.RefFiles("s.xlsx", "m.xlsx", "l.xlsx", "t.xlsx", "i.xlsx"),
                MAX_BYTES));
    }

    // ── resolveFolder ────────────────────────────────────────────────────────

    @Test
    void resolveFolder_returnsFolderInfo_forARelativePath() throws Exception {
        DriveItemInfo info = service.resolveFolder("Demo Happy Path Cohort");

        assertThat(info.isFolder()).isTrue();
        assertThat(info.name()).isEqualTo("Demo Happy Path Cohort");
        assertThat(info.itemId()).isEqualTo("Demo Happy Path Cohort");
        assertThat(info.siteId()).isEqualTo(SITE_ID);
    }

    @Test
    void resolveFolder_alsoAcceptsAFullUrl_soACohortCanBeConfiguredEitherWay() throws Exception {
        DriveItemInfo info = service.resolveFolder(WEB_BASE + "/Demo%20Happy%20Path%20Cohort");

        assertThat(info.itemId()).isEqualTo("Demo Happy Path Cohort");
    }

    @Test
    void resolveFolder_rejectsAFile_theWayGate1Expects() {
        assertThatThrownBy(() ->
            service.resolveFolder("Demo Happy Path Cohort/Lab Scores/Module 1 Grading.xlsx"))
            .isInstanceOf(GraphItemTypeException.class)
            .hasMessageContaining("not a folder");
    }

    @Test
    void resolveFolder_rejectsAMissingFolder() {
        assertThatThrownBy(() -> service.resolveFolder("No Such Cohort"))
            .isInstanceOf(GraphAccessException.class)
            .hasMessageContaining("Cannot access");
    }

    @Test
    void resolveFolder_rejectsAnUnsanctionedSite() {
        FixtureDriveService elsewhere = newService("a-completely-different-site");

        assertThatThrownBy(() -> elsewhere.resolveFolder("Demo Happy Path Cohort"))
            .isInstanceOf(GraphSiteViolationException.class)
            .hasMessageContaining("outside the sanctioned");
    }

    @Test
    void resolveFolder_refusesToEscapeTheFixtureRoot() {
        assertThatThrownBy(() -> service.resolveFolder("../../etc"))
            .isInstanceOf(GraphAccessException.class);
    }

    // ── listChildren ─────────────────────────────────────────────────────────

    @Test
    void listChildren_returnsFoldersAndFiles_sortedAndWithoutNoise() throws Exception {
        List<DriveItemInfo> children = service.listChildren("fixture-drive", "Demo Happy Path Cohort");

        assertThat(children).extracting(DriveItemInfo::name)
            .containsExactly("Lab Scores", "Reference Data");
        assertThat(children).allMatch(DriveItemInfo::isFolder);
    }

    @Test
    void listChildren_skipsDotfilesAndSpreadsheetLockFiles() throws Exception {
        List<DriveItemInfo> children = service.listChildren("fixture-drive",
            "Demo Happy Path Cohort/Lab Scores");

        assertThat(children).extracting(DriveItemInfo::name)
            .containsExactly("Module 1 Grading.xlsx");
    }

    @Test
    void listChildren_rejectsAFile() {
        assertThatThrownBy(() -> service.listChildren("fixture-drive",
            "Demo Happy Path Cohort/Lab Scores/Module 1 Grading.xlsx"))
            .isInstanceOf(GraphAccessException.class)
            .hasMessageContaining("not a folder");
    }

    // ── getItem ──────────────────────────────────────────────────────────────

    @Test
    void getItem_carriesEverythingTheAuditRowRecords() throws Exception {
        DriveItemDetails details = service.getItem("fixture-drive", relative(workbook));

        assertThat(details.name()).isEqualTo("Module 1 Grading.xlsx");
        assertThat(details.parentFolderName()).isEqualTo("Lab Scores");
        assertThat(details.sizeBytes()).isEqualTo("workbook-v1".length());
        assertThat(details.hasQuickXorHash()).isTrue();
        assertThat(details.versionId()).startsWith("c:");
        assertThat(details.webUrl()).startsWith(WEB_BASE).contains("Module%201%20Grading.xlsx");
    }

    // ── change detection — the contract that matters ─────────────────────────

    @Test
    void hashAndVersionAreStable_whenContentIsUntouched() throws Exception {
        DriveItemDetails first = service.getItem("fixture-drive", relative(workbook));
        DriveItemDetails second = service.getItem("fixture-drive", relative(workbook));

        assertThat(second.quickXorHash()).isEqualTo(first.quickXorHash());
        assertThat(second.versionId()).isEqualTo(first.versionId());
    }

    @Test
    void hashAndVersionBothMove_whenContentChanges() throws Exception {
        DriveItemDetails before = service.getItem("fixture-drive", relative(workbook));

        Files.writeString(workbook, "workbook-v2");
        DriveItemDetails after = service.getItem("fixture-drive", relative(workbook));

        assertThat(after.quickXorHash()).isNotEqualTo(before.quickXorHash());
        assertThat(after.versionId()).isNotEqualTo(before.versionId());
    }

    @Test
    void identicalContentInTwoPlaces_hashesTheSame_soACopyIsNotAChange() throws Exception {
        Path copy = Files.writeString(scores.resolve("Module 2 Grading.xlsx"), "workbook-v1");

        assertThat(service.getItem("fixture-drive", relative(copy)).quickXorHash())
            .isEqualTo(service.getItem("fixture-drive", relative(workbook)).quickXorHash());
    }

    // ── downloadFile ─────────────────────────────────────────────────────────

    @Test
    void downloadFile_returnsTheBytes() throws Exception {
        byte[] bytes = service.downloadFile("fixture-drive", relative(workbook));

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("workbook-v1");
    }

    @Test
    void downloadFile_enforcesTheSameSizeCapAsProduction() throws Exception {
        Files.write(workbook, new byte[(int) MAX_BYTES + 1]);

        assertThatThrownBy(() -> service.downloadFile("fixture-drive", relative(workbook)))
            .isInstanceOf(GraphAccessException.class)
            .hasMessageContaining("maximum allowed size");
    }

    @Test
    void downloadFile_rejectsAFolder() {
        assertThatThrownBy(() -> service.downloadFile("fixture-drive", "Demo Happy Path Cohort"))
            .isInstanceOf(GraphAccessException.class)
            .hasMessageContaining("is a folder");
    }

    @Test
    void constructor_failsFast_whenTheFixtureRootIsNotADirectory() {
        FixtureDriveProperties missing =
            new FixtureDriveProperties(root.resolve("nope").toString(), SITE_ID, WEB_BASE);

        assertThatThrownBy(() -> new FixtureDriveService(missing,
            new AzureGraphProperties(null, null, null, SITE_ID),
            new SharePointProperties("Reference Data", "Lab Scores",
                new SharePointProperties.RefFiles("s", "m", "l", "t", "i"), MAX_BYTES)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a directory");
    }

    private String relative(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }
}
