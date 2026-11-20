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

@Service
@Profile("!test")
public class OpenAiTranscriptionService implements TranscriptionService {

  // reference: https://github.com/openai/whisper/blob/main/whisper/transcribe.py
  // threshold for considering if the segment contains silence
  private static final float NO_SPEECH_THRESHOLD = 0.6f;
  private static final float LOG_PROB_THRESHOLD = -1.0f;

  private final OpenAIClient openAIClient;
  private final GlobalStatsService globalStatsService;

  public OpenAiTranscriptionService(OpenAIClient openAIClient, GlobalStatsService globalStatsService) {
    this.openAIClient = openAIClient;
    this.globalStatsService = globalStatsService;

  }

  @Override
  public SubtitleResponse transcribe(MultipartFile audioFile) throws IOException {

    TranscriptionCreateParams params = createRequest(audioFile);
    TranscriptionCreateResponse response = openAIClient.audio().transcriptions().create(params);

    // change to verbose instead of string
    TranscriptionVerbose verbose = response.verbose().orElseThrow();

    globalStatsService.addUsage(verbose.duration());

    return new SubtitleResponse(verbose.duration(), convertToSegment(verbose));
  }

  private TranscriptionCreateParams createRequest(MultipartFile audioFile) throws IOException {

    // create a MultipartField request for OpenAI model
    return TranscriptionCreateParams.builder()
        .file(
            MultipartField.<InputStream>builder() // OpenAI SDK requires MultipartField of InputStream data type
                .value(audioFile.getInputStream())
                .filename("recording.webm")
                .build())
        .model(AudioModel.WHISPER_1)
        .language("en")
        .temperature(0.0f)
        .responseFormat(AudioResponseFormat.VERBOSE_JSON)
        .timestampGranularities(List.of(TimestampGranularity.WORD, TimestampGranularity.SEGMENT)) // This tells the
                                                                                                  // OpenAI Whisper API
                                                                                                  // what level of
                                                                                                  // timestamp detail
                                                                                                  // we want in the
                                                                                                  // transcription
                                                                                                  // response.
        .build();
  }

  private List<SubtitleSegment> convertToSegment(TranscriptionVerbose verbose) {
    List<TranscriptionSegment> openAiOutputSegments = verbose.segments().orElse(List.of());

    List<TranscriptionWord> openAiOutputWords = verbose.words().orElse(List.of());

    List<SubtitleSegment> convertedSegments = new ArrayList<>();

    for (TranscriptionSegment segment : openAiOutputSegments) {
      if (isSegmentSilent(segment)) {
        continue;
      }

      if (isRepeatedLetter(segment.text())) {
        continue;
      }

      convertedSegments.add(new SubtitleSegment(segment.start(), segment.end(), segment.text().trim(),
          findWordsOfSegment(openAiOutputWords, segment.start(), segment.end())));
    }

    return convertedSegments;
  }

  private boolean isSegmentSilent(TranscriptionSegment segment) {
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
  private boolean isRepeatedLetter(String text) {
    String letters = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");

    return letters.length() > 2 && letters.chars().distinct().count() == 1;
  }

  private List<SubtitleWord> findWordsOfSegment(List<TranscriptionWord> words, double start, double end) {
    List<SubtitleWord> matchedWords = new ArrayList<>();

    for (TranscriptionWord word : words) {
      if (word.start() >= start && word.start() < end) {
        matchedWords.add(new SubtitleWord(word.word(), word.start(), word.end()));
      }
    }

    return matchedWords;
  }

}
