package com.amalitech.labresultsvalidator.domain.user.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.user.dto.ProvisionInstructorRequest;
import com.amalitech.labresultsvalidator.domain.user.dto.ProvisionInstructorResponse;
import com.amalitech.labresultsvalidator.domain.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/instructors")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<ProvisionInstructorResponse>> provisionInstructor(
            @Valid @RequestBody ProvisionInstructorRequest request
    ) {
        ProvisionInstructorResponse response = userService.provisionInstructor(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Instructor account created successfully", response));
    }
}
