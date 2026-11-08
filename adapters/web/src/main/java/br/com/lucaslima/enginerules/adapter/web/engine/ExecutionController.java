package br.com.lucaslima.enginerules.adapter.web.engine;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.lucaslima.enginerules.application.execution.port.in.ExecutePolicyUseCase;

@RestController
public class ExecutionController {

    static final String VERSION_HEADER = "X-Policy-Version";
    static final String CONSUMER_HEADER = "X-Consumer-Id";
    static final String ROUTE_HEADER = "X-Policy-Route";

    private final ExecutePolicyUseCase executePolicy;

    public ExecutionController(ExecutePolicyUseCase executePolicy) {
        this.executePolicy = executePolicy;
    }

    @PostMapping("/policies/{name}/executions")
    public ResponseEntity<Map<String, Object>> execute(
            @PathVariable("name") String name,
            @RequestBody(required = false) Map<String, Object> input,
            @RequestParam(name = "version", required = false) String versionParameter,
            @RequestHeader(name = VERSION_HEADER, required = false) String versionHeader,
            @RequestParam(name = "noCache", required = false, defaultValue = "false") boolean noCacheParameter,
            @RequestHeader(name = "Cache-Control", required = false) String cacheControl,
            @RequestHeader(name = CONSUMER_HEADER, required = false) String consumerId) {
        boolean noCache = noCacheParameter || (cacheControl != null && cacheControl.contains("no-cache"));
        String version = versionParameter != null ? versionParameter : versionHeader;
        ExecutePolicyUseCase.Result result = executePolicy.execute(new ExecutePolicyUseCase.Command(
                name, version, input == null ? Map.of() : input, Optional.ofNullable(consumerId), noCache));
        return ResponseEntity.status(ExecutionResponses.httpStatus(result.report().outcome()))
                .header(VERSION_HEADER, result.version())
                .header(ROUTE_HEADER, result.route())
                .body(ExecutionResponses.envelope(result.report().outcome()));
    }
}
