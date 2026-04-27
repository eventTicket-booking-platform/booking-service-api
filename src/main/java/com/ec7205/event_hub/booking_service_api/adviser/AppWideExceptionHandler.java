package com.ec7205.event_hub.booking_service_api.adviser;


import com.ec7205.event_hub.booking_service_api.exception.BadRequestException;
import com.ec7205.event_hub.booking_service_api.exception.ConflictException;
import com.ec7205.event_hub.booking_service_api.exception.PaymentFailedException;
import com.ec7205.event_hub.booking_service_api.exception.ResourceNotFoundException;
import com.ec7205.event_hub.booking_service_api.exception.UnauthorizedActionException;
//import com.ec7205.event_hub.booking_service_api.exceptions.EntryNotFoundException;
import com.ec7205.event_hub.booking_service_api.utils.StandardResponseDto;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class AppWideExceptionHandler {
//    @ExceptionHandler(EntryNotFoundException.class)
//    public ResponseEntity<StandardResponseDto> handleEntryNotFoundException(EntryNotFoundException ex) {
//        return new ResponseEntity<StandardResponseDto>(
//                new StandardResponseDto(404,ex.getMessage(),ex),
//                HttpStatus.NOT_FOUND
//        );
//    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<StandardResponseDto> handleResourceNotFoundException(ResourceNotFoundException ex) {
        return new ResponseEntity<>(
                new StandardResponseDto(404, ex.getMessage(), ex),
                HttpStatus.NOT_FOUND
        );
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<StandardResponseDto> handleBadRequestException(BadRequestException ex) {
        return new ResponseEntity<>(
                new StandardResponseDto(400, ex.getMessage(), ex),
                HttpStatus.BAD_REQUEST
        );
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<StandardResponseDto> handleConflictException(ConflictException ex) {
        return new ResponseEntity<>(
                new StandardResponseDto(409, ex.getMessage(), ex),
                HttpStatus.CONFLICT
        );
    }

    @ExceptionHandler(UnauthorizedActionException.class)
    public ResponseEntity<StandardResponseDto> handleUnauthorizedActionException(UnauthorizedActionException ex) {
        return new ResponseEntity<>(
                new StandardResponseDto(403, ex.getMessage(), ex),
                HttpStatus.FORBIDDEN
        );
    }

    @ExceptionHandler(PaymentFailedException.class)
    public ResponseEntity<StandardResponseDto> handlePaymentFailedException(PaymentFailedException ex) {
        return new ResponseEntity<>(
                new StandardResponseDto(402, ex.getMessage(), ex),
                HttpStatus.PAYMENT_REQUIRED
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<StandardResponseDto> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(msg -> msg != null && !msg.isBlank())
                .collect(Collectors.joining(", "));

        if (message.isBlank()) {
            message = "Invalid request payload";
        }

        return new ResponseEntity<>(
                new StandardResponseDto(400, message, ex),
                HttpStatus.BAD_REQUEST
        );
    }

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<StandardResponseDto> handleFeignException(FeignException ex) {
        HttpStatus status = HttpStatus.resolve(ex.status());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }

        String message = ex.contentUTF8();
        if (message == null || message.isBlank()) {
            message = "Upstream service request failed";
        }

        log.error("Upstream service request failed with status {}: {}", ex.status(), ex.getMessage());

        return new ResponseEntity<>(
                new StandardResponseDto(status.value(), message, null),
                status
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<StandardResponseDto> handleUnexpectedException(Exception ex) {
        log.error("Unhandled booking-service exception", ex);
        return new ResponseEntity<>(
                new StandardResponseDto(500, "Internal server error", null),
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }
}
