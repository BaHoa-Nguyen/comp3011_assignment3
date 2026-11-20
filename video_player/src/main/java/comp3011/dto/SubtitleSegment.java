package comp3011.dto;

import java.util.List;

public record SubtitleSegment(double start, double end, String outputText, List<SubtitleWord> words) {
}
