package br.com.lucaslima.enginerules.adapter.web.common;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import br.com.lucaslima.enginerules.application.shared.InvalidRequestException;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.definition.InvalidPolicyDocumentException;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompilationException;
import br.com.lucaslima.enginerules.domain.rollout.RolloutException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<Map<String, Object>> catalog(CatalogException failure) {
        return ResponseEntity.status(ErrorResponses.statusOf(failure.code()))
                .body(ErrorResponses.body(failure.code().code(), failure.getMessage()));
    }

    @ExceptionHandler(RolloutException.class)
    public ResponseEntity<Map<String, Object>> rollout(RolloutException failure) {
        return ResponseEntity.status(ErrorResponses.statusOf(failure.code()))
                .body(ErrorResponses.body(failure.code().code(), failure.getMessage()));
    }

    @ExceptionHandler(PolicyCompilationException.class)
    public ResponseEntity<Map<String, Object>> compilation(PolicyCompilationException failure) {
        return ResponseEntity.status(422).body(ErrorResponses.body("policy_compilation_failed",
                "the policy does not compile", failure.violations().stream().map(Object::toString).toList()));
    }

    @ExceptionHandler(InvalidPolicyDocumentException.class)
    public ResponseEntity<Map<String, Object>> document(InvalidPolicyDocumentException failure) {
        return ResponseEntity.status(400).body(ErrorResponses.body("invalid_policy_document",
                "the policy document is malformed", failure.problems()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> invalidRequest(InvalidRequestException failure) {
        return ResponseEntity.status(400).body(ErrorResponses.body("invalid_request", failure.getMessage()));
    }
}
