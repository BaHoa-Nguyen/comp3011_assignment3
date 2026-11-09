package comp3011.assignment1.service;

import comp3011.assignment1.dto.SubtitleResponse;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

public interface TranscriptionService {
  SubtitleResponse transcribe(MultipartFile audioFile) throws IOException;
}
