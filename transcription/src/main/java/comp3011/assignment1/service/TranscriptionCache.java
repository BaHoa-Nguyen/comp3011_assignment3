package comp3011.assignment1.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Component;

import comp3011.assignment1.dto.SubtitleResponse;
import comp3011.assignment1.dto.SubtitleSegment;
import comp3011.assignment1.dto.SubtitleWord;

/**
 * Remembers which pieces of sound have already been transcribed and paid for.
 *
 * <p>
 * The fast path is the exact same bytes, which covers replaying the same video
 * or a renamed copy of it. The slower path recognises the same speech inside a
 * different video file: a short excerpt, a trimmed clip, or a re-encoded copy
 * starts at a different place, but the shape of the sound is still the same.
 * In that case the old transcripts are moved onto the new clip's clock and
 * OpenAI is not called again.
 * </p>
 *
 * <p>
 * The memory belongs to the running server, so restarting the server starts
 * this empty again.
 * </p>
 */
@Component
public class TranscriptionCache {

  // About eleven hours of distinct pieces, far more than one viewing session
  // needs. The exact store is cheap, so it can be generous.
  private static final int MAX_EXACT_TRANSCRIPTIONS = 4096;

  // The sound matcher compares against recent pieces only. About forty minutes
  // of sound is enough for an excerpt played after a video, and keeps the
  // search quick.
  private static final int MAX_RECENT_TRANSCRIPTIONS = 512;

  // A match must cover at least half a second of real sound. Shorter overlaps
  // are too easy to confuse with an unrelated quiet moment.
  private static final int MINIMUM_MATCH_FRAMES = 10;

  // Real matches score 0.93 or better even after lossy re-encoding; unrelated
  // sound stays below about 0.70.
  private static final double MINIMUM_MATCH_SIMILARITY = 0.85;

  // If a little of the clip is new, it is cheaper to reuse the old transcript
  // than to pay for the whole clip again.
  private static final double REQUIRED_SOUND_COVERAGE = 0.95;

  // A clip is normally covered by one or two older pieces: one before the old
  // chunk boundary and one after it.
  private static final int MAXIMUM_MATCH_ATTEMPTS = 4;

  private static final double SHORTEST_CAPTION_SECONDS = 0.05;

  private final Map<String, SubtitleResponse> exactTranscriptions = new ConcurrentHashMap<>();

  private final Deque<CachedTranscription> recentTranscriptions = new ConcurrentLinkedDeque<>();

  /**
   * Returns a transcript for this sound if the server has already paid for it,
   * or null when it has not.
   */
  public SubtitleResponse findTranscription(byte[] audioBytes) {
    SubtitleResponse exactTranscript = exactTranscriptions.get(checksumOf(audioBytes));

    if (exactTranscript != null) {
      return exactTranscript;
    }

    AudioFingerprint fingerprint = AudioFingerprint.fromWav(audioBytes);

    if (fingerprint == null) {
      return null; // not the player's WAV format: exact matching only
    }

    if (!fingerprint.hasSound()) {
      // Silence contains no words. Once anything has been transcribed, there
      // is nothing new to learn by paying for silence again.
      if (recentTranscriptions.isEmpty() && exactTranscriptions.isEmpty()) {
        return null;
      }

      return new SubtitleResponse(fingerprint.durationSeconds(), List.of());
    }

    List<FingerprintMatch> matches = findMatches(fingerprint.frameCodes());

    if (matches == null) {
      return null;
    }

    return buildResponse(fingerprint.durationSeconds(), matches);
  }

  /**
   * Stores a transcript that was just paid for, so the same sound is free next
   * time.
   */
  public void rememberTranscription(byte[] audioBytes, SubtitleResponse response) {
    rememberExactTranscription(checksumOf(audioBytes), response);

    AudioFingerprint fingerprint = AudioFingerprint.fromWav(audioBytes);

    if (fingerprint == null || fingerprint.frameCodes().length == 0) {
      return;
    }

    recentTranscriptions.addLast(
        new CachedTranscription(fingerprint.frameCodes(), List.copyOf(response.segments())));

    while (recentTranscriptions.size() > MAX_RECENT_TRANSCRIPTIONS) {
      recentTranscriptions.pollFirst();
    }
  }

  private void rememberExactTranscription(String checksum, SubtitleResponse response) {
    // shortcut: a plain bounded map, not a least-recently-used store. If one
    // server session ever transcribes more than a few hours of distinct sound,
    // swap in an LRU so old pieces survive a clear.
    if (exactTranscriptions.size() >= MAX_EXACT_TRANSCRIPTIONS) {
      exactTranscriptions.clear();
    }

    exactTranscriptions.put(checksum, response);
  }

  // The new clip may start anywhere inside an older ten-second piece, so every
  // 50 ms alignment is tried. The best match is accepted, then the search runs
  // again for any sound the first match did not cover. That is how a clip that
  // crosses an old chunk boundary is rebuilt from both sides.
  private List<FingerprintMatch> findMatches(int[] newFrameCodes) {
    int soundFrameCount = countSoundFrames(newFrameCodes);

    if (soundFrameCount < MINIMUM_MATCH_FRAMES) {
      return null;
    }

    boolean[] coveredFrames = new boolean[newFrameCodes.length];
    int coveredSoundFrames = 0;
    List<FingerprintMatch> matches = new ArrayList<>();

    for (int attempt = 0; attempt < MAXIMUM_MATCH_ATTEMPTS; attempt++) {
      FingerprintMatch bestMatch = null;

      for (CachedTranscription cached : recentTranscriptions) {
        FingerprintMatch candidate = findBestAlignment(newFrameCodes, coveredFrames, cached);

        if (isBetterMatch(candidate, bestMatch)) {
          bestMatch = candidate;
        }
      }

      if (bestMatch == null) {
        break;
      }

      matches.add(bestMatch);
      coveredSoundFrames += markCoveredFrames(newFrameCodes, coveredFrames, bestMatch);

      if ((double) coveredSoundFrames / soundFrameCount >= REQUIRED_SOUND_COVERAGE) {
        break;
      }
    }

    if ((double) coveredSoundFrames / soundFrameCount < REQUIRED_SOUND_COVERAGE) {
      return null; // too much of this clip is new: let OpenAI transcribe it all
    }

    return matches;
  }

  private static boolean isBetterMatch(FingerprintMatch candidate, FingerprintMatch currentBest) {
    if (candidate == null) {
      return false;
    }

    return currentBest == null
        || candidate.newlyCoveredFrames() > currentBest.newlyCoveredFrames()
        || (candidate.newlyCoveredFrames() == currentBest.newlyCoveredFrames()
            && candidate.similarity() > currentBest.similarity());
  }

  private static FingerprintMatch findBestAlignment(int[] newFrameCodes, boolean[] coveredFrames,
      CachedTranscription cached) {
    int[] cachedFrameCodes = cached.frameCodes();
    FingerprintMatch bestMatch = null;
    // A positive shift means the new clip starts later inside the old piece.
    // The bounds cover every alignment with at least half a second of overlap,
    // including a short old chunk that sits at the end of a longer new clip.
    for (int shift = -(newFrameCodes.length - MINIMUM_MATCH_FRAMES);
        shift <= cachedFrameCodes.length - MINIMUM_MATCH_FRAMES; shift++) {
      int fromFrame = Math.max(0, -shift);
      int toFrame = Math.min(newFrameCodes.length, cachedFrameCodes.length - shift);

      if (toFrame - fromFrame < MINIMUM_MATCH_FRAMES) {
        continue;
      }

      int soundFrames = 0;
      int matchingFrames = 0;
      int newlyCoveredFrames = 0;

      for (int frame = fromFrame; frame < toFrame; frame++) {
        if (!AudioFingerprint.isSoundFrame(newFrameCodes[frame])) {
          continue;
        }

        soundFrames++;

        if (AudioFingerprint.isMatchingFrame(newFrameCodes[frame], cachedFrameCodes[frame + shift])) {
          matchingFrames++;

          if (!coveredFrames[frame]) {
            newlyCoveredFrames++;
          }
        }
      }

      if (soundFrames < MINIMUM_MATCH_FRAMES || newlyCoveredFrames == 0) {
        continue;
      }

      double similarity = (double) matchingFrames / soundFrames;

      if (similarity < MINIMUM_MATCH_SIMILARITY) {
        continue;
      }

      FingerprintMatch candidate = new FingerprintMatch(
          cached, shift, fromFrame, toFrame, similarity, newlyCoveredFrames);

      if (isBetterMatch(candidate, bestMatch)) {
        bestMatch = candidate;
      }
    }

    return bestMatch;
  }

  // Once an alignment is accepted, the whole matched stretch is treated as
  // handled. A lossy encoder changes a few frames inside a sentence, and those
  // frames are still the same speech, not a reason to pay for the clip again.
  private static int markCoveredFrames(int[] newFrameCodes, boolean[] coveredFrames,
      FingerprintMatch match) {
    int newlyCoveredFrames = 0;

    for (int frame = match.fromFrame(); frame < match.toFrame(); frame++) {
      if (coveredFrames[frame] || !AudioFingerprint.isSoundFrame(newFrameCodes[frame])) {
        continue;
      }

      coveredFrames[frame] = true;
      newlyCoveredFrames++;
    }

    return newlyCoveredFrames;
  }

  private static int countSoundFrames(int[] frameCodes) {
    int soundFrames = 0;

    for (int frameCode : frameCodes) {
      if (AudioFingerprint.isSoundFrame(frameCode)) {
        soundFrames++;
      }
    }

    return soundFrames;
  }

  // Moves the old captions onto the new clip's clock. The same sentence may
  // arrive twice when the clip crosses an old chunk boundary, so overlapping
  // copies are trimmed away.
  private static SubtitleResponse buildResponse(double durationSeconds, List<FingerprintMatch> matches) {
    List<SubtitleSegment> movedSegments = new ArrayList<>();

    for (FingerprintMatch match : matches) {
      double secondsToMove = -match.shiftFrames() * AudioFingerprint.HOP_SECONDS;
      double matchedStart = match.fromFrame() * AudioFingerprint.HOP_SECONDS;
      double matchedEnd = match.toFrame() * AudioFingerprint.HOP_SECONDS;

      for (SubtitleSegment segment : match.source().segments()) {
        double movedStart = segment.start() + secondsToMove;
        double movedEnd = segment.end() + secondsToMove;

        if (movedEnd <= matchedStart || movedStart >= matchedEnd) {
          continue; // this sentence belongs outside the part that matched
        }

        SubtitleSegment movedSegment = moveIntoTimeline(segment, secondsToMove, durationSeconds);

        if (movedSegment != null) {
          movedSegments.add(movedSegment);
        }
      }
    }

    movedSegments.sort(Comparator.comparingDouble(SubtitleSegment::start));

    return new SubtitleResponse(durationSeconds, removeOverlaps(movedSegments));
  }

  private static SubtitleSegment moveIntoTimeline(SubtitleSegment segment, double secondsToMove,
      double durationSeconds) {
    double start = clampToClip(segment.start() + secondsToMove, durationSeconds);
    double end = clampToClip(segment.end() + secondsToMove, durationSeconds);

    if (end - start < SHORTEST_CAPTION_SECONDS) {
      return null;
    }

    List<SubtitleWord> movedWords = new ArrayList<>();

    for (SubtitleWord word : segment.words()) {
      double wordStart = clampToClip(word.start() + secondsToMove, durationSeconds);
      double wordEnd = clampToClip(word.end() + secondsToMove, durationSeconds);

      if (wordEnd > wordStart) {
        movedWords.add(new SubtitleWord(word.word(), wordStart, wordEnd));
      }
    }

    return new SubtitleSegment(start, end, segment.outputText(), movedWords);
  }

  private static double clampToClip(double seconds, double durationSeconds) {
    return Math.max(0, Math.min(durationSeconds, seconds));
  }

  private static List<SubtitleSegment> removeOverlaps(List<SubtitleSegment> segments) {
    List<SubtitleSegment> result = new ArrayList<>();

    for (SubtitleSegment segment : segments) {
      if (!result.isEmpty()) {
        SubtitleSegment previous = result.get(result.size() - 1);

        if (segment.start() < previous.end() - 0.001) {
          double newStart = previous.end();

          if (segment.end() - newStart < SHORTEST_CAPTION_SECONDS) {
            continue; // this copy is already covered
          }

          segment = new SubtitleSegment(newStart, segment.end(), segment.outputText(), segment.words());
        }
      }

      result.add(segment);
    }

    return result;
  }

  // A checksum is a short fingerprint of the bytes. The same file always gives
  // the same checksum; a different file almost never does.
  private static String checksumOf(byte[] audioBytes) {
    try {
      byte[] checksum = MessageDigest.getInstance("SHA-256").digest(audioBytes);
      return HexFormat.of().formatHex(checksum);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java platform provides SHA-256", e);
    }
  }

  private record CachedTranscription(int[] frameCodes, List<SubtitleSegment> segments) {
  }

  private record FingerprintMatch(CachedTranscription source, int shiftFrames, int fromFrame, int toFrame,
      double similarity, int newlyCoveredFrames) {
  }
}
