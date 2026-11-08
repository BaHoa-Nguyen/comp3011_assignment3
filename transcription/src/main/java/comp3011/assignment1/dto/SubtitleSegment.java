package comp3011.assignment1.dto;

import java.util.List;

/**
 * This is the segment that will store the words in a list and assemble them
 * into a readead String for the reader
 */
public record SubtitleSegment(double start, double end, String outputText, List<SubtitleWord> words) {

}
