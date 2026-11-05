package comp3011.assignment1.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import comp3011.assignment1.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  // helper method to generate the error response
  private ResponseEntity<ErrorResponse> generateErrorResponse(HttpStatus status, String message,
      HttpServletRequest request) {

    ErrorResponse error = new ErrorResponse(
        Instant.now(),
        status.value(),
        status.getReasonPhrase(),
        message,
        request.getRequestURI());

    return ResponseEntity.status(status).body(error);
  }

  // internal server error
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleInternalError(Exception e, HttpServletRequest request) {

    // log error interally
    log.error("Error: ", e);

    return generateErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected server error occurred", request);
  }

  // graceful shutdown conflict error
  @ExceptionHandler(ShutdownInProgressException.class)
  public ResponseEntity<ErrorResponse> handleShutdownConflictError(Exception e, HttpServletRequest request) {

    log.error("Error: ", e);

    return generateErrorResponse(HttpStatus.CONFLICT, "Graceful shutdown is already in progress.", request);
  }

  @ExceptionHandler(IOException.class)
  public ResponseEntity<ErrorResponse> handleIOException(IOException e, HttpServletRequest request) {

    log.error("Error: ", e);

    return generateErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process audio file", request);
  }

}
