package comp3011.assignment1.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import comp3011.assignment1.dto.ShutdownResponse;
import comp3011.assignment1.dto.UptimeResponse;
import comp3011.assignment1.service.AdminService;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

  private final AdminService adminService;

  public AdminController(AdminService adminService) {
    this.adminService = adminService;
  }

  @GetMapping("/uptime")
  public ResponseEntity<UptimeResponse> getUptime() {
    return ResponseEntity.ok(adminService.getServerUptime());
  }

  @PostMapping("/shutdown")
  public ResponseEntity<ShutdownResponse> shutdownServer() {

    adminService.shutdownServer();

    return ResponseEntity
        .accepted()
        .body(new ShutdownResponse("Graceful shutdown requested."));

  }

}
