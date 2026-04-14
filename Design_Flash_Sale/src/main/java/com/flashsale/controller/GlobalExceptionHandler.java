package com.flashsale.controller;

import com.flashsale.model.dto.FlashSaleDTOs.ErrorResponse;
import javax.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;

/**
 * Global exception handler that translates exceptions into proper HTTP
 * responses.
 * Maps domain exceptions to the error codes defined in the API specification.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handle business logic violations (SOLD_OUT, ALREADY_PURCHASED,
     * SALE_NOT_ACTIVE, etc.)
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(
            IllegalStateException ex, WebRequest request) {

        HttpStatus status;
        String msg = ex.getMessage();
        if ("SOLD_OUT".equals(msg) || "ALREADY_PURCHASED".equals(msg) || "SALE_NOT_ACTIVE".equals(msg)) {
            status = HttpStatus.CONFLICT; // 409
        } else if ("PAYMENT_FAILED".equals(msg)) {
            status = HttpStatus.UNPROCESSABLE_ENTITY; // 422
        } else if ("CONTENTION_TOO_HIGH".equals(msg) || "SERVICE_BUSY".equals(msg)) {
            status = HttpStatus.SERVICE_UNAVAILABLE; // 503
        } else {
            status = HttpStatus.BAD_REQUEST;
        }

        ErrorResponse error = ErrorResponse.builder()
                .error(ex.getMessage())
                .message(getHumanMessage(ex.getMessage()))
                .timestamp(LocalDateTime.now())
                .path(request.getDescription(false))
                .build();

        log.warn("Business error: {} → {}", ex.getMessage(), status);
        return ResponseEntity.status(status).body(error);
    }

    /**
     * Handle invalid request parameters (Bean Validation failures).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, WebRequest request) {

        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

        ErrorResponse error = ErrorResponse.builder()
                .error("VALIDATION_ERROR")
                .message(message)
                .timestamp(LocalDateTime.now())
                .path(request.getDescription(false))
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handle resource not found.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            IllegalArgumentException ex, WebRequest request) {

        HttpStatus status = ex.getMessage() != null && ex.getMessage().contains("NOT_FOUND")
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;

        ErrorResponse error = ErrorResponse.builder()
                .error(ex.getMessage())
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .path(request.getDescription(false))
                .build();

        return ResponseEntity.status(status).body(error);
    }

    /**
     * Handle optimistic lock conflicts that bubble up.
     */
    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            OptimisticLockException ex, WebRequest request) {

        ErrorResponse error = ErrorResponse.builder()
                .error("CONCURRENT_MODIFICATION")
                .message("Another transaction modified this resource. Please retry.")
                .timestamp(LocalDateTime.now())
                .path(request.getDescription(false))
                .build();

        log.warn("Optimistic lock exception", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Handle duplicate key violations (e.g., duplicate purchase attempt).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateKey(
            DataIntegrityViolationException ex, WebRequest request) {

        String message = "A conflicting record already exists";
        if (ex.getMessage() != null && ex.getMessage().contains("uq_user_sale")) {
            message = "You have already purchased from this flash sale";
        }

        ErrorResponse error = ErrorResponse.builder()
                .error("ALREADY_PURCHASED")
                .message(message)
                .timestamp(LocalDateTime.now())
                .path(request.getDescription(false))
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Catch-all for unexpected errors.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(
            Exception ex, WebRequest request) {

        log.error("Unexpected error", ex);

        ErrorResponse error = ErrorResponse.builder()
                .error("INTERNAL_ERROR")
                .message("An unexpected error occurred. Please try again later.")
                .timestamp(LocalDateTime.now())
                .path(request.getDescription(false))
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    // ───────────── Helper ─────────────

    private String getHumanMessage(String errorCode) {
        if ("SOLD_OUT".equals(errorCode)) {
            return "This flash sale is sold out";
        } else if ("ALREADY_PURCHASED".equals(errorCode)) {
            return "You have already purchased from this sale";
        } else if ("SALE_NOT_ACTIVE".equals(errorCode)) {
            return "This sale has not started yet or has already ended";
        } else if ("PAYMENT_FAILED".equals(errorCode)) {
            return "Payment was declined. Please try a different payment method.";
        } else if ("CONTENTION_TOO_HIGH".equals(errorCode)) {
            return "Server is extremely busy. Please try again in a moment.";
        } else if ("SERVICE_BUSY".equals(errorCode)) {
            return "Service temporarily unavailable. Please retry.";
        } else {
            return errorCode;
        }
    }
}
