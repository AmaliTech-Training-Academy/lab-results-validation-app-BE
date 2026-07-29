package com.amalitech.labresultsvalidator.infrastructure.graph;

import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.item.items.ItemsRequestBuilder;
import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.ItemReference;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
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

    private GraphDriveService graphDriveService;

    @BeforeEach
    void setUp() {
        graphDriveService = new GraphDriveService(graphServiceClient, azureGraphProperties, sharePointProperties);

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
        when(driveItemItemRequestBuilder.get())
            .thenReturn(itemWithParentPath("Results.xlsx", "/drives/drive-1/root:/Cohort X/Lab Scores/Scenario 1"));

        DriveItemDetails details = graphDriveService.getItem(DRIVE_ID, ITEM_ID);

        assertThat(details.name()).isEqualTo("Results.xlsx");
        assertThat(details.parentFolderName()).isEqualTo("Scenario 1");
    }

    @Test
    void resolvesTheScoresFolderItselfAsTheParentWhenTheFileIsNotInAScenarioSubfolder() {
        when(driveItemItemRequestBuilder.get())
            .thenReturn(itemWithParentPath("Direct.xlsx", "/drives/drive-1/root:/Cohort X/Lab Scores"));

        DriveItemDetails details = graphDriveService.getItem(DRIVE_ID, ITEM_ID);

        assertThat(details.name()).isEqualTo("Direct.xlsx");
        assertThat(details.parentFolderName()).isEqualTo("Lab Scores");
    }

    @Test
    void returnsNullParentFolderNameWhenGraphDoesNotProvideAParentPath() {
        when(driveItemItemRequestBuilder.get()).thenReturn(itemWithParentPath("Orphan.xlsx", null));

        DriveItemDetails details = graphDriveService.getItem(DRIVE_ID, ITEM_ID);

        assertThat(details.name()).isEqualTo("Orphan.xlsx");
        assertThat(details.parentFolderName()).isNull();
    }
}