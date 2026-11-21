package comp3011.assignment1.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

@Profile("!test")
@Configuration
public class OpenAiClientConfig {

  @Bean
  public OpenAIClient openAIClient(@Value("${openai.api-key-file:}") String apiKeyFile) throws IOException {

    Path keyFile = apiKeyFile.isBlank() ? null : Path.of(apiKeyFile);

    if (keyFile != null && Files.isReadable(keyFile)) {
      String apiKey = Files.readString(keyFile).trim();

      if (!apiKey.isEmpty()) {
        return OpenAIOkHttpClient.builder().fromEnv().apiKey(apiKey).build();
      }
    }

    return OpenAIOkHttpClient.fromEnv();
  }

}
