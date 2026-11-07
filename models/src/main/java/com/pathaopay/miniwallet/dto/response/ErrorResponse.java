package com.pathaopay.miniwallet.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorResponse(
        @JsonProperty("error")
        ErrorDetail error
) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ErrorDetail(code, message));
    }

    public record ErrorDetail(
            @JsonProperty("code")
            String code,

            @JsonProperty("message")
            String message
    ) {}
}
