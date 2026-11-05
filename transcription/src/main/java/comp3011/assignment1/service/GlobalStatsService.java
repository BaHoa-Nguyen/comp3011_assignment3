package comp3011.assignment1.service;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import comp3011.assignment1.dto.GlobalStatsResponse;

@Service
public class GlobalStatsService {

  // using AtomicLong for thread-safe, non-blocking operation
  private final AtomicLong inputTokens = new AtomicLong(0);
  private final AtomicLong outputTokens = new AtomicLong(0);

  private final Object statLock = new Object();

  public void addUsage(long input, long output) {

    // add the input and output amount
    // to the inputTokens and outputTokens respectively
    // using addAndGet to get the updated value

    // one thread for one pair of input-output tokens
    // Thread 1 -> (inputTokens, outputTokens)
    synchronized (statLock) {
      inputTokens.addAndGet(input);
      outputTokens.addAndGet(output);
    }
  }

  public GlobalStatsResponse getGlobalStats() {
    // reading pairs of input-output tokens consistently
    synchronized (statLock) {
      return new GlobalStatsResponse(
          inputTokens.get(),
          outputTokens.get());
    }
  }
}
