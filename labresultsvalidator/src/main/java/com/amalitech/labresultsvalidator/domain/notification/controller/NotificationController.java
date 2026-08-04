package com.amalitech.labresultsvalidator.domain.notification.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.notification.dto.NotificationResponse;
import com.amalitech.labresultsvalidator.domain.notification.dto.NotificationSettingsResponse;
import com.amalitech.labresultsvalidator.domain.notification.dto.UpdateNotificationSettingsRequest;
import com.amalitech.labresultsvalidator.domain.notification.service.NotificationDispatchService;
import com.amalitech.labresultsvalidator.domain.notification.service.NotificationQueryService;
import com.amalitech.labresultsvalidator.domain.notification.service.NotificationSettingsService;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
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
        description = "Queues a HELD/FAILED notification to be sent now and returns immediately — the actual "
            + "SMTP send happens off-thread. Poll GET /{id} for the resulting SENT/FAILED status. A "
            + "notification already SENT is a no-op. Covers both the manual-send and retry actions.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
            description = "Send queued"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "No notification with that ID")
    })
    @PostMapping("/{id}/send")
    public ResponseEntity<ApiResponse<NotificationResponse>> send(@PathVariable UUID id) {
        NotificationResponse current = notificationQueryService.getById(id);
        notificationDispatchService.sendAsync(id, currentUserId());
        return ResponseEntity.accepted().body(ApiResponse.success("Send queued.", current));
    }

    @Operation(summary = "Send all held notifications for a sync run",
        description = "Queues every currently PENDING HELD notification for the given sync job to be sent "
            + "now and returns immediately with the count queued — the actual SMTP sends happen off-thread, "
            + "one after another. AUTO notifications are unaffected; they dispatch automatically at sync-run "
            + "end. Poll GET / (filtered by syncJobId/status) for outcomes.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
            description = "Send-all queued")
    })
    @PostMapping("/send-all")
    public ResponseEntity<ApiResponse<Long>> sendAll(@RequestParam UUID syncJobId) {
        long count = notificationDispatchService.countHeldPending(syncJobId);
        notificationDispatchService.sendAllHeldAsync(syncJobId, currentUserId());
        return ResponseEntity.accepted().body(ApiResponse.success(count + " notification(s) queued.", count));
    }

    @Operation(summary = "Dismiss a pending notification",
        description = "Marks a PENDING notification as SKIPPED so it will never be sent — e.g. the digest "
            + "is no longer relevant. Only a PENDING notification can be dismissed; anything already SENT, "
            + "FAILED, or SKIPPED is rejected.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Notification dismissed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "No notification with that ID"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "Notification is not PENDING and cannot be dismissed")
    })
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<ApiResponse<NotificationResponse>> dismiss(@PathVariable UUID id) {
        notificationDispatchService.dismiss(id, currentUserId());
        return ResponseEntity.ok(ApiResponse.success("Notification dismissed.", notificationQueryService.getById(id)));
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