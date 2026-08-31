package com.amalitech.labresultsvalidator.infrastructure.graph;

import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.item.items.ItemsRequestBuilder;
import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.File;
import com.microsoft.graph.models.Hashes;
import com.microsoft.graph.models.ItemReference;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphDriveServiceTest {

    private static final String DRIVE_ID = "drive-1";
    private static final String ITEM_ID = "item-1";

    @Mock
    private GraphServiceClient graphServiceClient;
    @Mock
    private AzureGraphProperties azureGraphProperties;
    @Mock
    private SharePointProperties sharePointProperties;

    @Mock
    private DrivesRequestBuilder drivesRequestBuilder;
    @Mock
    private DriveItemRequestBuilder driveItemRequestBuilder;
    @Mock
    private ItemsRequestBuilder itemsRequestBuilder;
    @Mock
    private DriveItemItemRequestBuilder driveItemItemRequestBuilder;

    private MicrosoftGraphDriveService graphDriveService;

    @BeforeEach
    void setUp() {
        // A real executor with a single attempt and a no-op sleeper: exercises the pass-through
        // without turning these into retry tests (GraphRetryExecutorTest covers that).
        GraphRetryExecutor retry = new GraphRetryExecutor(
            new GraphRetryProperties(1, 0L, 0L, 0L, 0L), millis -> { });

        graphDriveService = new MicrosoftGraphDriveService(
            graphServiceClient, azureGraphProperties, sharePointProperties, retry);

        when(graphServiceClient.drives()).thenReturn(drivesRequestBuilder);
        when(drivesRequestBuilder.byDriveId(DRIVE_ID)).thenReturn(driveItemRequestBuilder);
        when(driveItemRequestBuilder.items()).thenReturn(itemsRequestBuilder);
        when(itemsRequestBuilder.byDriveItemId(ITEM_ID)).thenReturn(driveItemItemRequestBuilder);
    }

    private DriveItem itemWithParentPath(String name, String parentPath) {
        DriveItem item = new DriveItem();
        item.setName(name);
        if (parentPath != null) {
            ItemReference parentReference = new ItemReference();
            parentReference.setPath(parentPath);
            item.setParentReference(parentReference);
        }
        return item;
    }

    @Test
    void resolvesTheImmediateParentFolderNameFromTheItemsParentPath() {
        when(driveItemItemRequestBuilder.get(any()))
            .thenReturn(itemWithParentPath("Results.xlsx", "/drives/drive-1/root:/Cohort X/Lab Scores/Scenario 1"));

        DriveItemDetails details = graphDriveService.getItem(DRIVE_ID, ITEM_ID);

        assertThat(details.name()).isEqualTo("Results.xlsx");
        assertThat(details.parentFolderName()).isEqualTo("Scenario 1");
    }

    @Test
    void resolvesTheScoresFolderItselfAsTheParentWhenTheFileIsNotInAScenarioSubfolder() {
        when(driveItemItemRequestBuilder.get(any()))
            .thenReturn(itemWithParentPath("Direct.xlsx", "/drives/drive-1/root:/Cohort X/Lab Scores"));

        DriveItemDetails details = graphDriveService.getItem(DRIVE_ID, ITEM_ID);

        assertThat(details.name()).isEqualTo("Direct.xlsx");
        assertThat(details.parentFolderName()).isEqualTo("Lab Scores");
    }

    @Test
    void returnsNullParentFolderNameWhenGraphDoesNotProvideAParentPath() {
        when(driveItemItemRequestBuilder.get(any())).thenReturn(itemWithParentPath("Orphan.xlsx", null));

        DriveItemDetails details = graphDriveService.getItem(DRIVE_ID, ITEM_ID);

        assertThat(details.name()).isEqualTo("Orphan.xlsx");
        assertThat(details.parentFolderName()).isNull();
    }

    @Test
    void readsTheChangeDetectionMetadataFromTheFileFacet() {
        DriveItem item = itemWithParentPath("Scores.xlsx", "/drives/drive-1/root:/Cohort X/Lab Scores");
        Hashes hashes = new Hashes();
        hashes.setQuickXorHash("zZ9k4TaQ==");
        File file = new File();
        file.setHashes(hashes);
        item.setFile(file);
        item.setCTag("\"c:{GUID},2\"");
        item.setSize(4096L);
        item.setWebUrl("https://tenant.sharepoint.com/Scores.xlsx");

        when(driveItemItemRequestBuilder.get(any())).thenReturn(item);

        DriveItemDetails details = graphDriveService.getItem(DRIVE_ID, ITEM_ID);

        assertThat(details.quickXorHash()).isEqualTo("zZ9k4TaQ==");
        assertThat(details.hasQuickXorHash()).isTrue();
        assertThat(details.versionId()).isEqualTo("\"c:{GUID},2\"");
        assertThat(details.sizeBytes()).isEqualTo(4096L);
        assertThat(details.webUrl()).isEqualTo("https://tenant.sharepoint.com/Scores.xlsx");
    }

    @Test
    void reportsNoHashWhenGraphOmitsTheFileFacet() {
        when(driveItemItemRequestBuilder.get(any()))
            .thenReturn(itemWithParentPath("NoFacet.xlsx", null));

        DriveItemDetails details = graphDriveService.getItem(DRIVE_ID, ITEM_ID);

        // Detection must not assume the hash exists — it falls back to comparing bytes.
        assertThat(details.quickXorHash()).isNull();
        assertThat(details.hasQuickXorHash()).isFalse();
    }
}
