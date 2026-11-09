package br.com.lucaslima.enginerules.app.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import br.com.lucaslima.enginerules.adapter.web.common.ApiExceptionHandler;
import br.com.lucaslima.enginerules.adapter.web.engine.ExecutionController;
import br.com.lucaslima.enginerules.adapter.web.engine.PreviewController;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(EngineProperties.class)
@Import({ExecutionController.class, PreviewController.class, ApiExceptionHandler.class})
public class EngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(EngineApplication.class, args);
    }
}
