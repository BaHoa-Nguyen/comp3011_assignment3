package comp3011.assignment1;

// JUnit testing
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
// Activates the "test" Spring profile.
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import comp3011.assignment1.dto.GlobalStatsResponse;
import comp3011.assignment1.dto.TestResult;
import comp3011.assignment1.service.GlobalStatsService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT) // create a random port for testing,
                                                                            // avoiding using the 8080 port for the
                                                                            // production code
@ActiveProfiles("test")
public class TranscriptionConcurrencyTest {

  private final String MOCK_AUDIO_CONTENT = "Mock audio content";

  @LocalServerPort
  private int port;

  @Autowired
  private GlobalStatsService globalStatsService;

  // use WebCClientBuilder to send the HTTP request to the
  // FakeTranscriptionService
  @Autowired
  private WebClient.Builder webClientBuilder;

  @Test
  void shouldHandle300ConcurrentRequest() throws Exception {
    // assume we have 300 requests running concurrently
    int numOfRequests = 300;

    // since we're using the FakeTranscriptionService which returns a string
    // UTF_8 would be convenient and efficient for handling simple mock text
    byte[] mockAudio = MOCK_AUDIO_CONTENT.getBytes(StandardCharsets.UTF_8);

    // Create a WebClient and point it to the real Spring Boot server, running on
    // the random port created earlier.
    // WebClient can handle asynchronous and non-blocking HTTP request
    WebClient webClient = webClientBuilder.baseUrl("http://localhost:" + port).build();

    // for storing 300 requests
    List<CompletableFuture<TestResult>> requestStorage = new ArrayList<>();

    // record the time immediately before sending the requests.
    // this measures the total test duration.
    long testStartTime = System.nanoTime();

    // Create and send 300 HTTP requests then store the Mono<TestResult> inside the
    // requestStorage
    for (int request = 0; request < numOfRequests; request++) {
      requestStorage.add(sendTranscriptionRequest(webClient, mockAudio));
    }

    // This future completes only when every request has completed.
    CompletableFuture<Void> allRequestsCompleted = CompletableFuture
        .allOf(requestStorage.toArray(new CompletableFuture[0])); // start with empty array

    // wait for all 300 requests to finish.
    // longer than 10 seconds => the test fails.

    allRequestsCompleted.get(10, TimeUnit.SECONDS);

    // calculate the elapsed time
    long totalTimeTaken = Duration.ofNanos(System.nanoTime() - testStartTime).toMillis();

    // convert to actual TestResult
    List<TestResult> results = requestStorage.stream()
        .map(future -> future.join())
        .toList();

    assertEquals(numOfRequests, results.size());

    // check each response
    for (int index = 0; index < results.size(); index++) {

      TestResult result = results.get(index);

      System.out.println(
          "Request " + (index + 1)
              + ": status=" + result.statusCode()
              + ", time=" + result.timeTaken()
              + " ms");

      assertEquals(200, result.statusCode());
      assertEquals("This is a mocked transcription response", result.body());

      assertTrue(
          result.timeTaken() < 5000,
          "Request took too long: "
              + result.timeTaken()
              + " ms");
    }

    GlobalStatsResponse stats = globalStatsService.getGlobalStats();

    assertEquals(3000, stats.inputTokens());
    assertEquals(1500, stats.outputTokens());

    System.out.println(
        "Completed "
            + numOfRequests
            + " requests in "
            + totalTimeTaken
            + " ms");
  }

  private CompletableFuture<TestResult> sendTranscriptionRequest(WebClient webClient, byte[] mockAudio) {

    // for multipart form data
    MultipartBodyBuilder requestBody = new MultipartBodyBuilder();

    // match the controller @RequestParam("audio") MultipartFile audioFile
    requestBody.part("audio", createAudioResource(mockAudio));

    // record the start time for this individual request
    long requestStartTime = System.nanoTime();

    // send the request
    return webClient.post()
        .uri("/api/v1/transcribe")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData(requestBody.build()))
        .exchangeToMono(response -> processResponse(response, requestStartTime))
        .toFuture();
  }

  private ByteArrayResource createAudioResource(byte[] audio) {
    return new ByteArrayResource(audio) {
      @Override
      public String getFilename() {
        return "recording.webm";
      }
    };
  }

  // eventually return a Mono<TestResult> object
  // Use Mono here because reading HTTP is asynchronous
  private Mono<TestResult> processResponse(ClientResponse response, long startTime) {

    int statusCode = response.statusCode().value();

    return response.bodyToMono(String.class) // return a String
        .defaultIfEmpty("")
        .map(responseBody -> {
          long timeTaken = Duration.ofNanos(System.nanoTime() - startTime).toMillis(); // convert to millisecond
          return new TestResult(statusCode, responseBody, timeTaken);
        });
  }
}
