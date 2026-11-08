package br.com.lucaslima.enginerules.integration.support;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

final class Applications {

    private Applications() {
    }

    static RunningApplication start(Class<?> application, Map<String, String> properties) {
        Map<String, String> overrides = new LinkedHashMap<>(properties);
        overrides.put("server.port", "0");
        String[] arguments = overrides.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(application)
                .web(WebApplicationType.SERVLET)
                .run(arguments);
        String port = context.getEnvironment().getRequiredProperty("local.server.port");
        return new RunningApplication(URI.create("http://localhost:" + port), context::close);
    }
}
