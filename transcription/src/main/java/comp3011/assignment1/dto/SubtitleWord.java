package comp3011.assignment1.dto;

/**
 * 
 * This is the Word record that holds the actual text from OpenAI
 * 
 */
public record SubtitleWord(
    String word,
    double start,
    double end) {

}
