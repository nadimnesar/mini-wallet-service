package com.pathaopay.miniwallet.advice;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorMessages;
import com.pathaopay.miniwallet.common.constant.AppConstants.Headers;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;
import com.pathaopay.miniwallet.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Map<String, HttpStatus> STATUS_BY_ERROR_CODE = Map.ofEntries(
            Map.entry(ErrorCodes.INVALID_AMOUNT, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCodes.INVALID_PHONE_NUMBER, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCodes.PAYMENT_DECLINED, HttpStatus.PAYMENT_REQUIRED),
            Map.entry(ErrorCodes.WALLET_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCodes.TRANSACTION_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCodes.DUPLICATE_WALLET, HttpStatus.CONFLICT),
            Map.entry(ErrorCodes.DUPLICATE_PHONE, HttpStatus.CONFLICT),
            Map.entry(ErrorCodes.DUPLICATE_GATEWAY_REF, HttpStatus.CONFLICT),
            Map.entry(ErrorCodes.INSUFFICIENT_BALANCE, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCodes.SAME_WALLET_TRANSFER, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCodes.IDEMPOTENCY_KEY_REUSED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCodes.PAYMENT_NOT_FOUND, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCodes.PAYMENT_MISMATCH, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCodes.PAYMENT_GATEWAY_ERROR, HttpStatus.BAD_GATEWAY));

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detailMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse(ErrorMessages.VALIDATION_FAILED);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCodes.VALIDATION_ERROR, detailMessage));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCodes.VALIDATION_ERROR, ErrorMessages.INVALID_PARAMETER_OR_HEADER));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String detailMessage = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .findFirst()
                .orElse(ErrorMessages.CONSTRAINT_VIOLATION);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCodes.VALIDATION_ERROR, detailMessage));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestHeader(MissingRequestHeaderException ex) {
        if (Headers.IDEMPOTENCY_KEY.equalsIgnoreCase(ex.getHeaderName())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of(ErrorCodes.MISSING_IDEMPOTENCY_KEY, ErrorMessages.IDEMPOTENCY_KEY_REQUIRED));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCodes.MISSING_HEADER, ErrorMessages.MISSING_HEADER.formatted(ex.getHeaderName())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        // A value Jackson cannot convert (e.g. a non-UUID user_id or an unknown payment_method) names its field.
        String message = ex.getMostSpecificCause() instanceof MismatchedInputException mismatch
                && !mismatch.getPath().isEmpty()
                ? ErrorMessages.INVALID_FIELD_VALUE.formatted(fieldPath(mismatch))
                : ErrorMessages.MALFORMED_JSON;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCodes.INVALID_PAYLOAD, message));
    }

    private static String fieldPath(MismatchedInputException ex) {
        return ex.getPath().stream()
                .map(ref -> ref.getPropertyName() != null ? ref.getPropertyName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCodes.INVALID_ARGUMENT, ErrorMessages.INVALID_ARGUMENT_PREFIX + ex.getName()));
    }

    /**
     * Every domain error is mapped by its error code, so a failure replayed from an Idempotency-Key
     * (which only stores code and message) gets exactly the same status as the original.
     */
    @ExceptionHandler(MiniWalletException.class)
    public ResponseEntity<ErrorResponse> handleMiniWalletException(MiniWalletException ex) {
        HttpStatus status = STATUS_BY_ERROR_CODE.getOrDefault(ex.getErrorCode(), HttpStatus.BAD_REQUEST);
        if (ex.isRetryable()) {
            log.warn("{}: {}", ex.getErrorCode(), ex.getMessage(), ex.getCause());
        }
        return ResponseEntity.status(status).body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ErrorCodes.NOT_FOUND, ErrorMessages.RESOURCE_NOT_FOUND));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of(ErrorCodes.METHOD_NOT_ALLOWED, ErrorMessages.METHOD_NOT_SUPPORTED.formatted(ex.getMethod())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error(ErrorMessages.UNHANDLED_EXCEPTION_LOG, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ErrorCodes.INTERNAL_SERVER_ERROR, ErrorMessages.UNEXPECTED_ERROR));
    }
}
