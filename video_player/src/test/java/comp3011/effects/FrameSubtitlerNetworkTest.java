package comp3011.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Checks that a short problem with the subtitle server does not cost a piece
 * of captions. The first request is refused, and the second one is answered.
 */
class FrameSubtitlerNetworkTest {

  @Test
  void asksForTheSamePieceAgainAfterTheServerRefusesTheFirstRequest() throws Exception {
    AtomicInteger requestsReceived = new AtomicInteger();
    byte[] answer = "{\"duration\":10.0,\"segments\":[]}".getBytes(StandardCharsets.UTF_8);

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/subtitles", exchange -> {
      // Fail only the first try, like a server that is briefly busy.
      if (requestsReceived.incrementAndGet() == 1) {
        exchange.sendResponseHeaders(500, -1);
      } else {
        exchange.sendResponseHeaders(200, answer.length);

        try (OutputStream body = exchange.getResponseBody()) {
          body.write(answer);
        }
      }

      exchange.close();
    });
    server.start();

    try {
      byte[] returnedAnswer = new FrameSubtitler().transcribeChunk(new byte[] { 1, 2, 3 }, 0,
          "http://127.0.0.1:" + server.getAddress().getPort() + "/subtitles");

      assertEquals(new String(answer, StandardCharsets.UTF_8),
          new String(returnedAnswer, StandardCharsets.UTF_8));
      assertEquals(2, requestsReceived.get(), "the same piece should be sent twice");
    } finally {
      server.stop(0);
    }
  }
}
