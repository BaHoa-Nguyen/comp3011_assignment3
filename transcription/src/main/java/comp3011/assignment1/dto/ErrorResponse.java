package comp3011.assignment1.dto;

import java.time.Instant;

public record ErrorResponse(

    Instant timestamp,
    int status,
    String error,
    String message,
    String path

) {
}
