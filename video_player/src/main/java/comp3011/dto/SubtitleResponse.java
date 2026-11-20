package comp3011.dto;

import java.util.List;

public record SubtitleResponse(double duration, List<SubtitleSegment> segments) {
}
