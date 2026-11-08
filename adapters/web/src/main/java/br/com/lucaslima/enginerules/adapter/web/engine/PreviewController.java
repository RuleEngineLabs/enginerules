package br.com.lucaslima.enginerules.adapter.web.engine;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import br.com.lucaslima.enginerules.application.execution.port.in.PreviewPolicyUseCase;
import br.com.lucaslima.enginerules.application.shared.InvalidRequestException;
import br.com.lucaslima.enginerules.domain.definition.PolicyDocument;

@RestController
public class PreviewController {

    private final PreviewPolicyUseCase previewPolicy;

    public PreviewController(PreviewPolicyUseCase previewPolicy) {
        this.previewPolicy = previewPolicy;
    }

    @PostMapping("/previews")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody Map<String, Object> request) {
        if (!request.containsKey("policy")) {
            throw new InvalidRequestException("body must contain 'policy' and optionally 'input'");
        }
        Object input = request.getOrDefault("input", Map.of());
        if (!(input instanceof Map<?, ?>)) {
            throw new InvalidRequestException("'input' must be an object");
        }
        PreviewPolicyUseCase.Result result = previewPolicy.preview(new PreviewPolicyUseCase.Command(
                PolicyDocument.read(request.get("policy")), (Map<String, Object>) input));
        return ResponseEntity.ok(ExecutionResponses.preview(result.writeStates(), result.report()));
    }
}
