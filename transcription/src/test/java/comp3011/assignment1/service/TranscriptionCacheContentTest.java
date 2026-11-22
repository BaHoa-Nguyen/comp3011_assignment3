package comp3011.assignment1.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import comp3011.assignment1.dto.SubtitleResponse;
import comp3011.assignment1.dto.SubtitleSegment;
import comp3011.assignment1.dto.SubtitleWord;

/**
 * Checks the promise behind "even smarter" caching: a clip cut from a video
 * that was already transcribed is recognised by the shape of its sound, even
 * though its bytes and chunk boundaries are different.
 */
class TranscriptionCacheContentTest {

  private static final int SAMPLE_RATE = 16_000;

  @Test
  void reusesCaptionsWhenTheClipStartsLaterInTheSameSound() {
    short[] fullSound = speechLikeSound(16, 1);
    TranscriptionCache cache = new TranscriptionCache();

    rememberFirstChunk(cache, fullSound);
    cache.rememberTranscription(wavOf(slice(fullSound, 10, 16)),
        new SubtitleResponse(6.0, List.of(segment(0.0, 3.0, "the last part"))));

    SubtitleResponse reused = cache.findTranscription(wavOf(slice(fullSound, 2, 12)));

    assertNotNull(reused, "the excerpt should be recognised as already transcribed");
    assertEquals(10.0, reused.duration(), 0.01);

    // The original first sentence started at 0 s, which is 2 s before the clip.
    assertEquals(0.0, reused.segments().get(0).start(), 0.01);
    assertEquals("the first sentence", reused.segments().get(0).outputText());
    assertTrue(reused.segments().stream().anyMatch(segment -> segment.outputText().contains("the last part")),
        "the part that came from the second old chunk should be included");
  }

  @Test
  void reusesCaptionsAfterGainAndSmallNoise() {
    short[] fullSound = speechLikeSound(16, 2);
    TranscriptionCache cache = new TranscriptionCache();

    rememberFirstChunk(cache, fullSound);
    cache.rememberTranscription(wavOf(slice(fullSound, 10, 16)),
        new SubtitleResponse(6.0, List.of(segment(0.0, 6.0, "the last part"))));

    short[] excerpt = slice(fullSound, 2, 12);
    quieterWithNoise(excerpt);

    SubtitleResponse reused = cache.findTranscription(wavOf(excerpt));

    assertNotNull(reused, "a re-encoded or quieter copy should still be recognised");
    assertFalse(reused.segments().isEmpty());
  }

  @Test
  void doesNotMistakeDifferentSoundForTheSameSpeech() {
    short[] firstSound = speechLikeSound(16, 3);
    short[] differentSound = speechLikeSound(10, 4);
    TranscriptionCache cache = new TranscriptionCache();

    rememberFirstChunk(cache, firstSound);
    cache.rememberTranscription(wavOf(slice(firstSound, 10, 16)),
        new SubtitleResponse(6.0, List.of(segment(0.0, 6.0, "the last part"))));

    assertNull(cache.findTranscription(wavOf(differentSound)),
        "unrelated speech must still be sent to OpenAI");
  }

  @Test
  void reusesSilenceOnlyAfterSomethingHasBeenTranscribed() {
    TranscriptionCache cache = new TranscriptionCache();
    byte[] silence = wavOf(new short[10 * SAMPLE_RATE]);

    assertNull(cache.findTranscription(silence), "the first silent piece keeps the old behaviour");

    cache.rememberTranscription(wavOf(speechLikeSound(10, 5)),
        new SubtitleResponse(10.0, List.of(segment(0.0, 10.0, "some speech"))));

    SubtitleResponse reused = cache.findTranscription(silence);

    assertNotNull(reused);
    assertTrue(reused.segments().isEmpty(), "silence has no captions");
  }

  @Test
  void keepsTheExactByteFastPath() {
    TranscriptionCache cache = new TranscriptionCache();
    byte[] sound = wavOf(speechLikeSound(10, 6));
    SubtitleResponse original = new SubtitleResponse(10.0, List.of(segment(0.0, 10.0, "exact")));

    cache.rememberTranscription(sound, original);

    assertEquals(original, cache.findTranscription(sound));
  }

  private static void rememberFirstChunk(TranscriptionCache cache, short[] fullSound) {
    cache.rememberTranscription(wavOf(slice(fullSound, 0, 10)), new SubtitleResponse(10.0, List.of(
        segment(0.0, 5.0, "the first sentence"),
        segment(5.0, 10.0, "the second sentence"))));
  }

  private static SubtitleSegment segment(double start, double end, String text) {
    return new SubtitleSegment(start, end, text, List.of(new SubtitleWord(text, start, end)));
  }

  // Reacts like a lossy encoder: the recording is quieter and carries a little
  // background noise, but the speech itself is unchanged.
  private static void quieterWithNoise(short[] samples) {
    Random random = new Random(7);

    for (int index = 0; index < samples.length; index++) {
      int quieter = samples[index] / 2;
      int noise = random.nextInt(41) - 20;
      samples[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, quieter + noise));
    }
  }

  // A stand-in for speech: short blocks of different tones and loudness. Real
  // speech is more varied, but this is enough to prove the matching rules.
  private static short[] speechLikeSound(int seconds, int seed) {
    short[] samples = new short[seconds * SAMPLE_RATE];
    Random random = new Random(seed);
    int position = 0;

    while (position < samples.length) {
      int blockLength = Math.min(SAMPLE_RATE / 4, samples.length - position);
      double frequency = 200 + random.nextInt(2_800);
      int amplitude = 500 + random.nextInt(6_000);

      for (int index = 0; index < blockLength; index++) {
        samples[position + index] = (short) (amplitude
            * Math.sin(2 * Math.PI * frequency * index / SAMPLE_RATE));
      }

      position += blockLength;
    }

    return samples;
  }

  private static short[] slice(short[] samples, double startSeconds, double endSeconds) {
    int from = (int) (startSeconds * SAMPLE_RATE);
    int to = Math.min(samples.length, (int) (endSeconds * SAMPLE_RATE));

    return Arrays.copyOfRange(samples, from, to);
  }

  // Builds the same simple WAV format the player sends.
  private static byte[] wavOf(short[] samples) {
    int dataBytes = samples.length * 2;
    ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);

    wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    wav.putInt(36 + dataBytes);
    wav.put("WAVE".getBytes(StandardCharsets.US_ASCII));
    wav.put("fmt ".getBytes(StandardCharsets.US_ASCII));
    wav.putInt(16);
    wav.putShort((short) 1); // uncompressed sound
    wav.putShort((short) 1); // one channel
    wav.putInt(SAMPLE_RATE);
    wav.putInt(SAMPLE_RATE * 2); // bytes per second
    wav.putShort((short) 2); // bytes per sample
    wav.putShort((short) 16); // bits per sample
    wav.put("data".getBytes(StandardCharsets.US_ASCII));
    wav.putInt(dataBytes);

    for (short sample : samples) {
      wav.putShort(sample);
    }

    return wav.array();
  }
}
