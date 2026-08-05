package com.amalitech.labresultsvalidator.domain.instructor.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.instructor.dto.InstructorContactResponse;
import com.amalitech.labresultsvalidator.domain.instructor.service.InstructorContactQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Instructors", description = "Instructor contacts imported from the SharePoint reference sheet")
@RestController
@RequestMapping("/api/v1/instructors")
@RequiredArgsConstructor
public class InstructorContactController {

    private final InstructorContactQueryService instructorContactQueryService;

    @Operation(
        summary = "List instructors",
        description = "Returns a paginated list of all instructor contacts, newest first."
    )
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<InstructorContactResponse>>> list(
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.success(
            "Instructors retrieved.", instructorContactQueryService.listAll(pageable)));
    }

    @Operation(
        summary = "Get instructor by ID",
        description = "Returns a single instructor contact by its ID."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Instructor retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "No instructor with that ID",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<InstructorContactResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
            "Instructor retrieved.", instructorContactQueryService.getById(id)));
    }
}
