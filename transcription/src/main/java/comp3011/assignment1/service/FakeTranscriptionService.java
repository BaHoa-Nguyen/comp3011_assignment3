package comp3011.assignment1.service;

import comp3011.assignment1.dto.SubtitleResponse;
import comp3011.assignment1.dto.SubtitleSegment;
import comp3011.assignment1.dto.SubtitleWord;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.context.annotation.Profile;

@Service
@Profile("test")
public class FakeTranscriptionService implements TranscriptionService {

  // each fake transcription "lasts" 10 seconds of audio
  private static final double FAKE_DURATION_SECONDS = 10.0;

  private final GlobalStatsService globalStatsService;

  public FakeTranscriptionService(GlobalStatsService globalStatsService) {
    this.globalStatsService = globalStatsService;
  }

  @Override
  public SubtitleResponse transcribe(MultipartFile audioFile) throws IOException {
    try {
      // simulating the slow blocking OpenAI request
      Thread.sleep(2000);
      // every call will add 10 and 5 to the input and output token respectively
      globalStatsService.addUsage(FAKE_DURATION_SECONDS);

      SubtitleWord word = new SubtitleWord("mock", 0.0, 1.0);
      SubtitleSegment segment = new SubtitleSegment(0.0, FAKE_DURATION_SECONDS, "This is a mock transcription response",
          List.of(word));

      return new SubtitleResponse(FAKE_DURATION_SECONDS, List.of(segment));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Transcription request is interrupted: ", e);
    }
  }

}
