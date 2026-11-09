package br.com.lucaslima.enginerules.app.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import br.com.lucaslima.enginerules.application.execution.port.in.ExecutePolicyUseCase;
import br.com.lucaslima.enginerules.application.execution.port.in.PreviewPolicyUseCase;

@SpringBootTest(properties = "enginerules.store-directory=build/test-store")
class EngineApplicationTest {

    @Autowired
    private ExecutePolicyUseCase executePolicy;

    @Autowired
    private PreviewPolicyUseCase previewPolicy;

    @Test
    void wiresTheExecutionUseCases() {
        assertNotNull(executePolicy);
        assertNotNull(previewPolicy);
    }
}
