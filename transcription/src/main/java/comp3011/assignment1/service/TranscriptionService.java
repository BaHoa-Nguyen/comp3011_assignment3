package comp3011.assignment1.service;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

public interface TranscriptionService {
  String transcribe(MultipartFile audioFile) throws IOException;
}
