package comp3011.assignment1.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.context.ConfigurableApplicationContext;

import comp3011.assignment1.dto.UptimeResponse;
import comp3011.assignment1.exception.ShutdownInProgressException;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AdminService {

  private final ConfigurableApplicationContext context;

  private Instant utcServerStart;

  private final double MILLI_TO_SECOND = 1000.0;
  
  // flag to check if the shutting down is in progress
  private final AtomicBoolean isServerShuttingDown = new AtomicBoolean(false);

  public AdminService(ConfigurableApplicationContext context) {
    this.context = context;
  }

  // retrieve the utcServerStart right after the Spring boot application starts
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    utcServerStart = Instant.now();
  }

  public UptimeResponse getServerUptime() {
    Instant utcNow = Instant.now();

    double serverUptimeSeconds = (utcNow.toEpochMilli() - utcServerStart.toEpochMilli()) / MILLI_TO_SECOND;

    return new UptimeResponse(utcServerStart, utcNow, serverUptimeSeconds);

  }

  public void shutdownServer() {

    // avoid shutting down conflict 
    if(!isServerShuttingDown.compareAndSet(false, true)) {
      throw new ShutdownInProgressException();
    }

    // create a new thread to shutdown the server
    new Thread(() -> context.close()).start();
  }
}
