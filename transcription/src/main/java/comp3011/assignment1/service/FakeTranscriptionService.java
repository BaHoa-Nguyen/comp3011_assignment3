package comp3011.assignment1.service;

import java.io.IOException;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.context.annotation.Profile;

@Service
@Profile("test")
public class FakeTranscriptionService implements TranscriptionService {

  private final GlobalStatsService globalStatsService;

  public FakeTranscriptionService(GlobalStatsService globalStatsService) {
    this.globalStatsService = globalStatsService;
  }

  @Override
  public String transcribe(MultipartFile audioFile) throws IOException {
    try {
      // simulating the slow blocking OpenAI request
      Thread.sleep(2000);
      // every call will add 10 and 5 to the input and output token respectively
      globalStatsService.addUsage(10, 5);
      return "This is a mocked transcription response";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Transcription request is interrupted: ", e);
    }
  }

}
