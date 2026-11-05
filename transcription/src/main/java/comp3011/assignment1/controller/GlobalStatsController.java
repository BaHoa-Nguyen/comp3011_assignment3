package comp3011.assignment1.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import comp3011.assignment1.dto.GlobalStatsResponse;
import comp3011.assignment1.service.GlobalStatsService;

@RestController
@RequestMapping("/api/v1/global")
public class GlobalStatsController {

  private final GlobalStatsService globalStatsService;

  public GlobalStatsController(GlobalStatsService globalStatsService) {
    this.globalStatsService = globalStatsService;
  }

  @GetMapping("/stats")
  public ResponseEntity<GlobalStatsResponse> getGlobalStats() {
    return ResponseEntity.ok(globalStatsService.getGlobalStats());
  }
}
