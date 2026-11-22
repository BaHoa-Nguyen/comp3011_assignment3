package comp3011.assignment1.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes what a piece of sound looks like, in a way that survives being
 * copied into a different video file.
 *
 * <p>
 * The same speech sounds the same even when the file around it is trimmed,
 * re-encoded, or made louder or quieter. This class turns the sound into a
 * short list of codes, one for every 50 milliseconds, so the server can
 * recognise it again without keeping the audio itself.
 * </p>
 *
 * <p>
 * The player always sends 16 kHz mono 16-bit WAV, so this class reads that
 * simple format directly and needs no audio decoder.
 * </p>
 */
public record AudioFingerprint(int[] frameCodes, double durationSeconds, double phaseSeconds) {

  // the player always sends this format, so the same numbers are used here
  public static final int SAMPLE_RATE = 16_000;
  public static final double HOP_SECONDS = 0.05;

  // each code describes 100 ms of sound, and a new code starts every 50 ms
  private static final int WINDOW_SAMPLES = 1_600;
  private static final int HOP_SAMPLES = 800;

  // A clip rarely starts exactly on the 50 ms grid used by the sound already
  // stored. Eight starting points 6.25 ms apart mean the clip still lines up
  // when it begins between two stored frames.
  private static final int PHASE_COUNT = 8;
  private static final int PHASE_SAMPLES = HOP_SAMPLES / PHASE_COUNT;

  // five and a half octaves of speech, from a low male voice to the bright
  // consonants that make words easy to tell apart
  private static final int[] BAND_FREQUENCIES = { 200, 300, 450, 675, 1_000, 1_500, 2_250, 3_400 };

  // one bit says "there is real sound here"; the other seven describe its shape
  private static final int VOICED_BIT = 1 << 7;
  private static final int SHAPE_BITS = 0x7F;

  // about -58 dBFS. Speech is far above this; room rumble and digital silence
  // are far below it.
  private static final int MINIMUM_SOUND_LEVEL = 40;

  public boolean hasSound() {
    for (int frameCode : frameCodes) {
      if ((frameCode & VOICED_BIT) != 0) {
        return true;
      }
    }

    return false;
  }

  // Returns null when the bytes are not a WAV file this class understands. The
  // caller then falls back to exact-byte caching, which is still correct, just
  // less clever.
  public static AudioFingerprint fromWav(byte[] wavBytes) {
    WavSound sound = readWavSound(wavBytes);

    if (sound == null) {
      return null;
    }

    return describeFromStart(sound.samples(), 0);
  }

  // The same sound described from slightly different starting points. Stored
  // transcripts always start on the normal grid, so trying these in turn lets a
  // clip that begins between two stored frames find its match.
  public static List<AudioFingerprint> fromWavAtEachPhase(byte[] wavBytes) {
    WavSound sound = readWavSound(wavBytes);

    if (sound == null) {
      return List.of();
    }

    List<AudioFingerprint> fingerprints = new ArrayList<>(PHASE_COUNT);

    for (int phase = 0; phase < PHASE_COUNT; phase++) {
      fingerprints.add(describeFromStart(sound.samples(), phase * PHASE_SAMPLES));
    }

    return fingerprints;
  }

  private static AudioFingerprint describeFromStart(short[] samples, int startSample) {
    int frameCount = samples.length - startSample < WINDOW_SAMPLES
        ? 0
        : 1 + (samples.length - startSample - WINDOW_SAMPLES) / HOP_SAMPLES;

    int[] frameCodes = new int[frameCount];

    for (int frameNumber = 0; frameNumber < frameCount; frameNumber++) {
      frameCodes[frameNumber] = describeFrame(samples, startSample + frameNumber * HOP_SAMPLES);
    }

    return new AudioFingerprint(frameCodes, (double) samples.length / SAMPLE_RATE,
        (double) startSample / SAMPLE_RATE);
  }

  // Turns one 100 ms window into eight bits: seven comparisons between
  // neighbouring frequency bands, plus a "this is real sound" flag. Comparing
  // neighbouring bands means the code does not change when the whole recording
  // is made louder or quieter.
  private static int describeFrame(short[] samples, int windowStart) {
    int frameCode = 0;
    double previousBandPower = bandPower(samples, windowStart, BAND_FREQUENCIES[0]);

    for (int band = 1; band < BAND_FREQUENCIES.length; band++) {
      double bandPower = bandPower(samples, windowStart, BAND_FREQUENCIES[band]);

      if (previousBandPower > bandPower) {
        frameCode |= 1 << (band - 1);
      }

      previousBandPower = bandPower;
    }

    if (rootMeanSquare(samples, windowStart) > MINIMUM_SOUND_LEVEL) {
      frameCode |= VOICED_BIT;
    }

    return frameCode;
  }

  // How much energy the window has in one frequency band. The Goertzel method
  // is a small, fast way to ask that one question without a full Fourier
  // transform.
  private static double bandPower(short[] samples, int windowStart, int frequency) {
    int bin = Math.round((float) WINDOW_SAMPLES * frequency / SAMPLE_RATE);
    double angle = 2 * Math.PI * bin / WINDOW_SAMPLES;
    double coefficient = 2 * Math.cos(angle);

    double previous = 0;
    double beforePrevious = 0;

    for (int index = 0; index < WINDOW_SAMPLES; index++) {
      double current = samples[windowStart + index] + coefficient * previous - beforePrevious;
      beforePrevious = previous;
      previous = current;
    }

    return Math.max(0, previous * previous + beforePrevious * beforePrevious - coefficient * previous * beforePrevious);
  }

  private static double rootMeanSquare(short[] samples, int windowStart) {
    double sumOfSquares = 0;

    for (int index = 0; index < WINDOW_SAMPLES; index++) {
      double sample = samples[windowStart + index];
      sumOfSquares += sample * sample;
    }

    return Math.sqrt(sumOfSquares / WINDOW_SAMPLES);
  }

  // Reads the two pieces of a WAV file this class needs: the format and the
  // samples. Returns null for anything else.
  private static WavSound readWavSound(byte[] wavBytes) {
    if (wavBytes == null || wavBytes.length < 12
        || !hasText(wavBytes, 0, "RIFF") || !hasText(wavBytes, 8, "WAVE")) {
      return null;
    }

    int audioFormat = 0;
    int channelCount = 0;
    int sampleRate = 0;
    int bitsPerSample = 0;
    int dataOffset = -1;
    int dataSize = 0;

    int position = 12;

    while (position + 8 <= wavBytes.length) {
      long chunkSize = Integer.toUnsignedLong(readInt(wavBytes, position + 4));
      int chunkStart = position + 8;

      if (hasText(wavBytes, position, "fmt ") && chunkSize >= 16 && chunkStart + 16 <= wavBytes.length) {
        audioFormat = readShort(wavBytes, chunkStart);
        channelCount = readShort(wavBytes, chunkStart + 2);
        sampleRate = readInt(wavBytes, chunkStart + 4);
        bitsPerSample = readShort(wavBytes, chunkStart + 14);
      } else if (hasText(wavBytes, position, "data")) {
        dataOffset = chunkStart;
        dataSize = (int) Math.min(chunkSize, wavBytes.length - chunkStart);
      }

      position = (int) Math.min((long) position + 8 + chunkSize + (chunkSize % 2), wavBytes.length);
    }

    boolean isUncompressed16Bit = audioFormat == 1 && bitsPerSample == 16;
    boolean isMonoOrStereo = channelCount == 1 || channelCount == 2;

    if (!isUncompressed16Bit || !isMonoOrStereo || sampleRate != SAMPLE_RATE || dataOffset < 0 || dataSize < 2) {
      return null;
    }

    int sampleCount = dataSize / (2 * channelCount);
    short[] samples = new short[sampleCount];

    for (int sampleNumber = 0; sampleNumber < sampleCount; sampleNumber++) {
      int byteOffset = dataOffset + sampleNumber * 2 * channelCount;
      int firstChannel = readShort(wavBytes, byteOffset);

      if (channelCount == 1) {
        samples[sampleNumber] = (short) firstChannel;
      } else {
        int secondChannel = readShort(wavBytes, byteOffset + 2);
        samples[sampleNumber] = (short) ((firstChannel + secondChannel) / 2);
      }
    }

    return new WavSound(samples);
  }

  private static boolean hasText(byte[] bytes, int offset, String text) {
    for (int index = 0; index < text.length(); index++) {
      if (bytes[offset + index] != (byte) text.charAt(index)) {
        return false;
      }
    }

    return true;
  }

  private static int readInt(byte[] bytes, int offset) {
    return (bytes[offset] & 0xFF)
        | ((bytes[offset + 1] & 0xFF) << 8)
        | ((bytes[offset + 2] & 0xFF) << 16)
        | ((bytes[offset + 3] & 0xFF) << 24);
  }

  private static int readShort(byte[] bytes, int offset) {
    return (bytes[offset] & 0xFF) | (bytes[offset + 1] << 8);
  }

  private record WavSound(short[] samples) {
  }

  // the cache compares frames with this rule, so it lives next to the code
  public static boolean isMatchingFrame(int newCode, int oldCode) {
    return (oldCode & VOICED_BIT) != 0
        && Integer.bitCount((newCode ^ oldCode) & SHAPE_BITS) <= 2;
  }

  public static boolean isSoundFrame(int frameCode) {
    return (frameCode & VOICED_BIT) != 0;
  }
}
