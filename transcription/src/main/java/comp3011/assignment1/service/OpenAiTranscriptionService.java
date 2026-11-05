package comp3011.assignment1.service;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.context.annotation.Profile;

import com.openai.client.OpenAIClient;
import com.openai.core.MultipartField;
import com.openai.models.audio.AudioModel;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.audio.transcriptions.TranscriptionCreateResponse;

@Service
@Profile("!test")
public class OpenAiTranscriptionService implements TranscriptionService {

  private final OpenAIClient openAIClient;
  private final GlobalStatsService globalStatsService;

  public OpenAiTranscriptionService(OpenAIClient openAIClient, GlobalStatsService globalStatsService) {
    this.openAIClient = openAIClient;
    this.globalStatsService = globalStatsService;

  }

  @Override
  public String transcribe(MultipartFile audioFile) throws IOException {

    TranscriptionCreateParams params = createRequest(audioFile);
    TranscriptionCreateResponse response = openAIClient.audio().transcriptions().create(params);

    updateTokenUsage(response);

    // use of orElseThrow() to unwrap the Optional part
    return response.transcription().orElseThrow().text();
  }

  private TranscriptionCreateParams createRequest(MultipartFile audioFile) throws IOException {

    // create a MultipartField request for OpenAI model
    return TranscriptionCreateParams.builder()
        .file(
            MultipartField.<InputStream>builder() // OpenAI SDK requires MultipartField of InputStream data type
                .value(audioFile.getInputStream())
                .filename("recording.webm")
                .build())
        .model(AudioModel.GPT_4O_TRANSCRIBE)
        .language("en")
        .temperature(0.0f)
        .responseFormat(AudioResponseFormat.JSON)
        .build();
  }

  private void updateTokenUsage(TranscriptionCreateResponse response) {
    // use of orElseThrow to ensure that the model does return a transcription
    // response
    // otherwise throws an error as transcription(), usage() and tokens()
    // all have Optional<> wrapper, meaning that they can return a null response
    // which can make it hard for debugging.

    long inputTokens = response.transcription()
        .orElseThrow()
        .usage()
        .orElseThrow()
        .tokens()
        .orElseThrow()
        .inputTokens();

    long outputTokens = response.transcription()
        .orElseThrow()
        .usage()
        .orElseThrow()
        .tokens()
        .orElseThrow()
        .outputTokens();

    globalStatsService.addUsage(inputTokens, outputTokens);
  }

}
