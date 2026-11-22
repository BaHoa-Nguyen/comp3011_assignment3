package comp3011.assignment1.service;

import comp3011.assignment1.dto.SubtitleResponse;
import comp3011.assignment1.dto.SubtitleSegment;
import comp3011.assignment1.dto.SubtitleWord;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale; // for language management

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Lazy;

import com.openai.client.OpenAIClient;
import com.openai.core.MultipartField;
import com.openai.models.audio.AudioModel;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.audio.transcriptions.TranscriptionCreateResponse;
import com.openai.models.audio.transcriptions.TranscriptionSegment;
import com.openai.models.audio.transcriptions.TranscriptionVerbose;
import com.openai.models.audio.transcriptions.TranscriptionWord;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams.TimestampGranularity;

/**
 * Turns a recorded piece of sound into text plus times by asking OpenAI's
 * Whisper model. Each call is billed, so the controller only calls this when
 * the same sound has not been transcribed before.
 */
@Service
@Profile("!test")
public class OpenAiTranscriptionService implements TranscriptionService {

  // Whisper's own silence check. A piece counts as silence only when the model
  // is fairly sure there was no speech AND it was unsure of what it "heard".
  // Both conditions together protect quiet real speech from being thrown away.
  // reference: https://github.com/openai/whisper/blob/main/whisper/transcribe.py
  private static final float NO_SPEECH_THRESHOLD = 0.6f;
  private static final float LOG_PROB_THRESHOLD = -1.0f;

  private final OpenAIClient openAIClient;
  private final GlobalStatsService globalStatsService;

  public OpenAiTranscriptionService(@Lazy OpenAIClient openAIClient, GlobalStatsService globalStatsService) {
    this.openAIClient = openAIClient;
    this.globalStatsService = globalStatsService;

  }

  @Override
  public SubtitleResponse createSubtitles(MultipartFile audioFile) throws IOException {

    TranscriptionCreateParams params = buildTranscriptionRequest(audioFile);
    TranscriptionCreateResponse response = openAIClient.audio().transcriptions().create(params);

    // change to verbose instead of string
    TranscriptionVerbose verbose = response.verbose().orElseThrow();

    // billing follows the length of the sound actually processed
    globalStatsService.addUsage(verbose.duration());

    return new SubtitleResponse(verbose.duration(), convertToSubtitleSegments(verbose));
  }

  private TranscriptionCreateParams buildTranscriptionRequest(MultipartFile audioFile) throws IOException {

    // create a MultipartField request for OpenAI model
    return TranscriptionCreateParams.builder()
        .file(
            MultipartField.<InputStream>builder() // OpenAI SDK requires MultipartField of InputStream data type
                .value(audioFile.getInputStream())
                // the ending of the name tells OpenAI which sound format to expect;
                // the player always sends the same WAV format, so this name is fixed
                .filename("recording.wav")
                .build())
        .model(AudioModel.WHISPER_1)
        .language("en")
        .temperature(0.0f) // steady wording: the same sound should transcribe the same way
        .responseFormat(AudioResponseFormat.VERBOSE_JSON)
        // ask for both sentence and word timings: sentence times decide when a
        // caption appears, word times are kept for finer timing later
        .timestampGranularities(List.of(TimestampGranularity.WORD, TimestampGranularity.SEGMENT))
        .build();
  }

  private List<SubtitleSegment> convertToSubtitleSegments(TranscriptionVerbose verbose) {
    List<TranscriptionSegment> openAiOutputSegments = verbose.segments().orElse(List.of());

    List<TranscriptionWord> openAiOutputWords = verbose.words().orElse(List.of());

    List<SubtitleSegment> convertedSegments = new ArrayList<>();

    for (TranscriptionSegment segment : openAiOutputSegments) {
      if (isSilentSegment(segment)) {
        continue; // Whisper's guess at noise, not words
      }

      if (isRepeatedLetterHallucination(segment.text())) {
        continue; // "Aaaaa..." carries no speech
      }

      convertedSegments.add(new SubtitleSegment(segment.start(), segment.end(), segment.text().trim(),
          findWordsWithinSegment(openAiOutputWords, segment.start(), segment.end())));
    }

    return convertedSegments;
  }

  private boolean isSilentSegment(TranscriptionSegment segment) {
    // The OpenAI SDK uses special types to represent values that might be unknown
    // or unrecognised. .asKnown() lets us handle that safely instead of assuming
    // the value is always available.
    float noSpeechProb = segment._noSpeechProb().asKnown().orElse(0.0f);
    float avglogProb = segment._avgLogprob().asKnown().orElse(0.0f);

    return noSpeechProb > NO_SPEECH_THRESHOLD && avglogProb < LOG_PROB_THRESHOLD;
  }

  // A segment of one letter repeated ("Aaaaaaaaa…") carries no speech.
  // Deliberately narrow: more than two letters and only one distinct letter, so
  // short real words like "Oh" or "No" are never dropped.
  private boolean isRepeatedLetterHallucination(String text) {
    String letters = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");

    return letters.length() > 2 && letters.chars().distinct().count() == 1;
  }

  private List<SubtitleWord> findWordsWithinSegment(List<TranscriptionWord> words, double start, double end) {
    List<SubtitleWord> matchedWords = new ArrayList<>();

    for (TranscriptionWord word : words) {
      if (word.start() >= start && word.start() < end) {
        matchedWords.add(new SubtitleWord(word.word(), word.start(), word.end()));
      }
    }

    return matchedWords;
  }

}
