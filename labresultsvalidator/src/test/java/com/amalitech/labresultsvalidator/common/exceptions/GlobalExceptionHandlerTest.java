package com.amalitech.labresultsvalidator.common.exceptions;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void deliberateIllegalArgumentEchoesItsMessageAsA400() {
        ResponseEntity<ApiResponse<Void>> response =
            handler.handleIllegalArgument(new IllegalArgumentException("End date must be after start date"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("End date must be after start date");
    }

    @Test
    void numberFormatExceptionIsNotMisreportedAsBadClientInputEvenThoughItIsAnIllegalArgumentException() {
        // A stray NumberFormatException means a bug in our own parsing code (e.g. an unguarded
        // Integer.parseInt), not deliberate business validation — must NOT be routed through
        // handleIllegalArgument's raw-message-echo, even though NumberFormatException IS an
        // IllegalArgumentException and would otherwise match that handler.
        ResponseEntity<ApiResponse<Void>> response =
            handler.handleNumberFormat(new NumberFormatException("For input string: \"abc\""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getMessage()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().getMessage()).doesNotContain("abc");
    }
}
