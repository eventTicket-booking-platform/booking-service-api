package com.ec7205.event_hub.booking_service_api.adviser;


import com.ec7205.event_hub.booking_service_api.exception.BadRequestException;
import com.ec7205.event_hub.booking_service_api.exception.ConflictException;
import com.ec7205.event_hub.booking_service_api.exception.PaymentFailedException;
import com.ec7205.event_hub.booking_service_api.exception.ResourceNotFoundException;
import com.ec7205.event_hub.booking_service_api.exception.UnauthorizedActionException;
//import com.ec7205.event_hub.booking_service_api.exceptions.EntryNotFoundException;
import com.ec7205.event_hub.booking_service_api.utils.StandardResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
