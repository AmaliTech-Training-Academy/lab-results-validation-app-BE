package com.amalitech.labresultsvalidator.domain.notification.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.notification.dto.NotificationResponse;
import com.amalitech.labresultsvalidator.domain.notification.dto.NotificationSettingsResponse;
import com.amalitech.labresultsvalidator.domain.notification.dto.UpdateNotificationSettingsRequest;
import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import com.amalitech.labresultsvalidator.domain.notification.service.NotificationDispatchService;
import com.amalitech.labresultsvalidator.domain.notification.service.NotificationQueryService;
import com.amalitech.labresultsvalidator.domain.notification.service.NotificationSettingsService;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Backs a separately-built frontend "Run-Review" screen — this only exposes the API surface. */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Instructor grading digests and admin alerts (outbox pattern)")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;
    private final NotificationDispatchService notificationDispatchService;
    private final NotificationSettingsService notificationSettingsService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "List notifications",
        description = "Returns a paginated list of staged notifications, newest first. Optionally filter by "
            + "cohort, sync job, status (PENDING/SENT/FAILED), type, or recipient kind (instructor/admin).")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> list(
        @RequestParam(required = false) UUID cohortId,
        @RequestParam(required = false) UUID syncJobId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String recipientKind,
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.success("Notifications retrieved.",
            notificationQueryService.search(cohortId, syncJobId, status, type, recipientKind, pageable)));
    }

    @Operation(summary = "Get a single notification",
        description = "Returns one notification's full detail, including its parsed row-issue list.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Notification found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "No notification with that ID")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<NotificationResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Notification retrieved.", notificationQueryService.getById(id)));
    }

    @Operation(summary = "Send (or retry) a notification",
        description = "Attempts to send a HELD/FAILED notification now. A notification already SENT is a "
            + "no-op success. Covers both the manual-send and retry actions.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Send attempted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "No notification with that ID")
    })
    @PostMapping("/{id}/send")
    public ResponseEntity<ApiResponse<NotificationResponse>> send(@PathVariable UUID id) {
        Notification sent = notificationDispatchService.sendNow(id, currentUserId());
        return ResponseEntity.ok(ApiResponse.success(
            "SENT".equals(sent.getStatus()) ? "Notification sent." : "Send attempted; see status/errorDetail.",
            NotificationResponse.from(sent, objectMapper)));
    }

    @Operation(summary = "Get the notification settings",
        description = "Returns the global auto-send-instructor-emails toggle.")
    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<NotificationSettingsResponse>> getSettings() {
        return ResponseEntity.ok(ApiResponse.success(
            "Notification settings retrieved.",
            NotificationSettingsResponse.from(notificationSettingsService.getSettings())));
    }

    @Operation(summary = "Update the notification settings",
        description = "Toggles auto-send-instructor-emails: OFF means instructor digests wait for a manual "
            + "send, ON means they dispatch automatically at sync-run end.")
    @PatchMapping("/settings")
    public ResponseEntity<ApiResponse<NotificationSettingsResponse>> updateSettings(
        @Valid @RequestBody UpdateNotificationSettingsRequest request
    ) {
        var updated = notificationSettingsService.updateAutoSendInstructorEmails(
            request.autoSendInstructorEmails(), currentUserId());
        return ResponseEntity.ok(ApiResponse.success(
            "Notification settings updated.", NotificationSettingsResponse.from(updated)));
    }

    private UUID currentUserId() {
        return ((User) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getId();
    }
}