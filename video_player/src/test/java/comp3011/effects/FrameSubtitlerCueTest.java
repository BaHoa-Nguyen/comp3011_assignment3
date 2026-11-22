package comp3011.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import comp3011.dto.Cue;
import comp3011.dto.SubtitleSegment;

/**
 * Checks the rules a caption must follow: short lines, at most two of them,
 * at least one second on screen, and times measured from the start of the
 * whole video rather than from the start of one ten-second piece.
 */
class FrameSubtitlerCueTest {

  @Test
  void splitsLongSpeechIntoCaptionsThatFitTheScreen() {
    String speech = "The quick brown fox jumps over the lazy dog, and then it keeps "
        + "running through the field until the sun goes down.";

    List<Cue> cues = FrameSubtitler.buildCues(
        List.of(new SubtitleSegment(0.0, 9.0, speech, List.of())), 0.0);

    assertTrue(cues.size() > 1, "a long piece of speech needs more than one caption");

    for (Cue cue : cues) {
      for (String line : cue.displayText().split("\n", -1)) {
        assertTrue(line.length() <= 42, "a line is too wide: " + line);
      }
    }
  }

  @Test
  void countsTimeFromTheStartOfTheWholeVideo() {
    List<Cue> cues = FrameSubtitler.buildCues(
        List.of(new SubtitleSegment(1.0, 3.0, "Hello there.", List.of())), 20.0);

    assertEquals(1, cues.size());
    assertEquals(21.0, cues.get(0).startSeconds(), 0.001);
    assertEquals(23.0, cues.get(0).endSeconds(), 0.001);
  }

  @Test
  void keepsAShortCaptionOnScreenForAtLeastOneSecond() {
    List<Cue> cues = FrameSubtitler.buildCues(List.of(
        new SubtitleSegment(0.0, 0.4, "Hi.", List.of()),
        new SubtitleSegment(3.0, 5.0, "How are you?", List.of())), 0.0);

    assertTrue(cues.get(0).endSeconds() - cues.get(0).startSeconds() >= 1.0,
        "a caption flashed for less than a second cannot be read");
  }

  @Test
  void breaksLongCaptionsIntoEnoughPiecesForTheSevenSecondRule() {
    // Nineteen seconds of evenly spaced words: three captions are needed, and
    // the natural break points land close enough to the fair share for each one
    // to stay under seven seconds.
    List<Cue> cues = FrameSubtitler.buildCues(
        List.of(new SubtitleSegment(0.0, 19.0, "word ".repeat(20), List.of())), 0.0);

    assertTrue(cues.size() >= 3, "long speech must be broken into several captions");

    for (Cue cue : cues) {
      assertTrue(cue.endSeconds() - cue.startSeconds() <= 7.0,
          "a caption stayed on screen too long");
    }
  }

  @Test
  void prefersToBreakAfterFullStop() {
    String speech = "This is one sentence. This is the rest of it.";

    List<Cue> cues = FrameSubtitler.buildCues(
        List.of(new SubtitleSegment(0.0, 10.0, speech, List.of())), 0.0);

    assertEquals("This is one sentence.", cues.get(0).displayText(),
        "the first caption should stop at the sentence boundary");
  }
}
