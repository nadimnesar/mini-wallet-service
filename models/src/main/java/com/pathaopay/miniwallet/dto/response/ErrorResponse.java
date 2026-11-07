package com.pathaopay.miniwallet.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Error body of every failed request: {@code {"error": {"code", "message"}}}. */
public record ErrorResponse(
        @JsonProperty("error")
        ErrorDetail error
) {
    /** Builds the response from an error code and a human-readable message. */
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ErrorDetail(code, message));
    }

    /** The error code (see {@code ErrorConstants.ErrorCode}) and its message. */
    public record ErrorDetail(
            @JsonProperty("code")
            String code,

            @JsonProperty("message")
            String message
    ) {}
}
