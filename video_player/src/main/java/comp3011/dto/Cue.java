package comp3011.dto;

/**
 * A caption that is ready to draw.
 * The two display lines are already joined with a '\n',
 * so the renderer only draws a string and never has to think about wrapping.
 */
public record Cue(double startSeconds, double endSeconds, String displayText) {

}
