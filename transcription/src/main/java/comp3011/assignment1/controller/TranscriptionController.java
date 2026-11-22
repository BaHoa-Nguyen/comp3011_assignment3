package comp3011.assignment1.controller;

import java.io.IOException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import comp3011.assignment1.dto.SubtitleResponse;
import comp3011.assignment1.service.TranscriptionCache;
import comp3011.assignment1.service.TranscriptionService;

@RestController
@RequestMapping("/api/v1")
public class TranscriptionController {

  private final TranscriptionService transcriptionService;
  private final TranscriptionCache transcriptionCache;

  public TranscriptionController(TranscriptionService transcriptionService,
      TranscriptionCache transcriptionCache) {
    this.transcriptionService = transcriptionService;
    this.transcriptionCache = transcriptionCache;
  }

  @PostMapping("/subtitles")
  // using ResponseEntity to handle HTTP response
  public ResponseEntity<SubtitleResponse> createSubtitles(@RequestParam("audio") MultipartFile audioFile)
      throws IOException {

    // in case the audio file is empty
    if (audioFile.isEmpty()) {

      return ResponseEntity
          .badRequest()
          .build();
    }

    byte[] audioBytes = audioFile.getBytes();

    // Replaying the same sound, or playing a clip cut from a video that has
    // already been transcribed, costs nothing.
    SubtitleResponse cachedTranscript = transcriptionCache.findTranscription(audioBytes);

    if (cachedTranscript != null) {
      return ResponseEntity.ok(cachedTranscript);
    }

    // no try-catch block here as it's handled by the global exception handler part
    SubtitleResponse subtitleResponse = transcriptionService.createSubtitles(audioFile);

    transcriptionCache.rememberTranscription(audioBytes, subtitleResponse);

    return ResponseEntity.ok(subtitleResponse);
  }
}
