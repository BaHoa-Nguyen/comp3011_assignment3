package comp3011.assignment1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import comp3011.assignment1.service.GlobalStatsService;

/**
 * Proves the promise behind caching: the same sound is paid for once, and
 * different sound is paid for again. This is what makes replaying a video, or
 * replaying a renamed copy, free.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // leave the shared usage counter clean for other tests
class TranscriptionCacheTest {

  @LocalServerPort
  private int port;

  @Autowired
  private GlobalStatsService globalStatsService;

  @Test
  void chargesForEachPieceOfSoundOnlyOnce() {
    WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + port).build();

    // start from wherever the counter already is, so this test does not depend
    // on the order the tests happen to run in
    double usageBefore = globalStatsService.getGlobalStats().usage();

    postAudio(webClient, "the first piece of sound");
    assertEquals(usageBefore + 10.0, globalStatsService.getGlobalStats().usage(),
        "new sound should be billed");

    postAudio(webClient, "the first piece of sound");
    assertEquals(usageBefore + 10.0, globalStatsService.getGlobalStats().usage(),
        "the same sound again should cost nothing");

    postAudio(webClient, "a completely different piece of sound");
    assertEquals(usageBefore + 20.0, globalStatsService.getGlobalStats().usage(),
        "different sound should be billed");
  }

  private void postAudio(WebClient webClient, String sound) {
    MultipartBodyBuilder requestBody = new MultipartBodyBuilder();

    requestBody.part("audio", new ByteArrayResource(sound.getBytes(StandardCharsets.UTF_8)) {
      @Override
      public String getFilename() {
        return "recording.wav";
      }
    });

    webClient.post()
        .uri("/api/v1/subtitles")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData(requestBody.build()))
        .exchangeToMono(response -> response.bodyToMono(String.class))
        .block(Duration.ofSeconds(10));
  }
}
