package br.com.lucaslima.enginerules.app.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import br.com.lucaslima.enginerules.adapter.web.admin.AuditController;
import br.com.lucaslima.enginerules.adapter.web.admin.PolicyAdminController;
import br.com.lucaslima.enginerules.adapter.web.admin.RolloutController;
import br.com.lucaslima.enginerules.adapter.web.common.ApiExceptionHandler;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AdminProperties.class)
@Import({PolicyAdminController.class, AuditController.class, RolloutController.class, ApiExceptionHandler.class})
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
