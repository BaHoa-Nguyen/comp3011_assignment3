package comp3011.assignment1.service;

import org.springframework.stereotype.Service;

import comp3011.assignment1.dto.GlobalStatsResponse;

@Service
public class GlobalStatsService {

  private double usageInSeconds = 0.0;

  private final Object statLock = new Object();

  public void addUsage(double seconds) {

    // add the input and output amount
    // to the inputTokens and outputTokens respectively
    // using addAndGet to get the updated value

    // one thread for one pair of input-output tokens
    // Thread 1 -> (inputTokens, outputTokens)
    synchronized (statLock) {
      usageInSeconds += seconds;
    }
  }

  public GlobalStatsResponse getGlobalStats() {
    // reading pairs of input-output tokens consistently
    synchronized (statLock) {
      return new GlobalStatsResponse(usageInSeconds);
    }
  }
}
