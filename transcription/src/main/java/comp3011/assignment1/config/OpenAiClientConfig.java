package comp3011.assignment1.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

@Profile("!test")
@Configuration
public class OpenAiClientConfig {

  @Bean
  public OpenAIClient openAIClient() {
    return OpenAIOkHttpClient.fromEnv(); // get the api key from the env
  }

}
