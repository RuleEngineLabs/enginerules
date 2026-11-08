package br.com.lucaslima.enginerules.app.admin;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import br.com.lucaslima.enginerules.application.catalog.port.in.CreatePolicyUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyQueries;

@SpringBootTest(properties = "enginerules.store-directory=build/test-store")
class AdminApplicationTest {

    @Autowired
    private CreatePolicyUseCase createPolicy;

    @Autowired
    private PolicyQueries policyQueries;

    @Test
    void wiresTheControlPlaneUseCases() {
        assertNotNull(createPolicy);
        assertNotNull(policyQueries);
    }
}
