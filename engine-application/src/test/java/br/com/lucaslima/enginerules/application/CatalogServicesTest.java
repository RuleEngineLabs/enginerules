package br.com.lucaslima.enginerules.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.adapter.expression.BuiltinExpressionLanguage;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditFilter;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.in.CreatePolicyUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyFilter;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicySummary;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyVersionDetails;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.RemoveVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.service.AuditQueryService;
import br.com.lucaslima.enginerules.application.catalog.service.CatalogLookup;
import br.com.lucaslima.enginerules.application.catalog.service.CreatePolicyService;
import br.com.lucaslima.enginerules.application.catalog.service.PolicyQueryService;
import br.com.lucaslima.enginerules.application.catalog.service.PublishVersionService;
import br.com.lucaslima.enginerules.application.catalog.service.RemoveVersionService;
import br.com.lucaslima.enginerules.application.execution.ExecutionLimits;
import br.com.lucaslima.enginerules.application.execution.port.in.PreviewPolicyUseCase;
import br.com.lucaslima.enginerules.application.execution.service.PreviewPolicyService;
import br.com.lucaslima.enginerules.application.shared.InvalidRequestException;
import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.application.shared.PageRequest;
import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.ChangeClassifier;
import br.com.lucaslima.enginerules.domain.catalog.VersionStatus;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateDefinition;
import br.com.lucaslima.enginerules.domain.execution.ExecutionOutcome;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompiler;
import br.com.lucaslima.enginerules.domain.execution.PolicyExecutor;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.ResultCache;

class CatalogServicesTest {

    private static final Actor ANA = new Actor("ana", Set.of("credit-team"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);

    private final InMemoryCatalog store = new InMemoryCatalog();
    private final InMemoryRollouts rollouts = new InMemoryRollouts();
    private final CatalogLookup catalog = new CatalogLookup(store, store);
    private final PolicyCompiler compiler = new PolicyCompiler(new BuiltinExpressionLanguage());
    private final CreatePolicyUseCase create = new CreatePolicyService(store, store, store, compiler, CLOCK);
    private final PublishVersionUseCase publish = new PublishVersionService(catalog, store, store, compiler,
            new ChangeClassifier(), CLOCK);
    private final RemoveVersionUseCase remove = new RemoveVersionService(catalog, store, store, rollouts, CLOCK);
    private final PolicyQueryService queries = new PolicyQueryService(catalog, store);

    @Test
    void refusesDuplicatePoliciesAndBlankBasedOn() {
        create.create(new CreatePolicyUseCase.Command("Gate", "credit-team", Set.of(), gate(200), ANA));

        assertEquals(CatalogErrorCode.POLICY_ALREADY_EXISTS, assertThrows(CatalogException.class, () ->
                create.create(new CreatePolicyUseCase.Command("Gate", "credit-team", Set.of(), gate(200), ANA))).code());
        assertEquals(CatalogErrorCode.BASED_ON_REQUIRED, assertThrows(CatalogException.class, () ->
                publish.publish(new PublishVersionUseCase.Command("Gate", " ", gate(201), ANA))).code());
        assertEquals(CatalogErrorCode.BASED_ON_REQUIRED, assertThrows(CatalogException.class, () ->
                publish.publish(new PublishVersionUseCase.Command("Gate", null, gate(201), ANA))).code());
    }

    @Test
    void removesVersionsAndAuditsTheRemoval() {
        create.create(new CreatePolicyUseCase.Command("Gate", "credit-team", Set.of(), gate(200), ANA));
        publish.publish(new PublishVersionUseCase.Command("Gate", "1.0.0", gate(201), ANA));

        remove.remove(new RemoveVersionUseCase.Command("Gate", "2.0.0", ANA));

        AuditRecord last = store.audits().get(store.audits().size() - 1);
        assertEquals("version.removed", last.action());
        assertEquals(Map.of("version", "2.0.0"), last.details());
        assertEquals(VersionStatus.REMOVED, queries.findVersion("Gate", "2.0.0").status());
    }

    @Test
    void answersPolicyAndVersionQueries() {
        create.create(new CreatePolicyUseCase.Command("Gate", "credit-team", Set.of(), gate(200), ANA));
        publish.publish(new PublishVersionUseCase.Command("Gate", "1.0.0", gate(201), ANA));

        PolicySummary summary = queries.findPolicy("Gate");
        PolicyVersionDetails stable = queries.findVersion("Gate", "stable");
        Page<PolicySummary> page = queries.listPolicies(PolicyFilter.of("G", " ", null), PageRequest.of(null, null));

        assertEquals("1.0.0", summary.stableVersion());
        assertEquals(Optional.of("2.0.0"), summary.pendingCandidate());
        assertEquals(2, summary.versions().size());
        assertEquals(VersionStatus.STABLE, stable.status());
        assertEquals("1.0.0", stable.version());
        assertEquals(gate(200), stable.definition());
        assertEquals(1, page.totalItems());
        assertEquals(PolicyFilter.of("G", null, null), PolicyFilter.of("G", "", null));
    }

    @Test
    void auditQueriesFilterAndPaginate() {
        create.create(new CreatePolicyUseCase.Command("Gate", "credit-team", Set.of(), gate(200), ANA));
        publish.publish(new PublishVersionUseCase.Command("Gate", "1.0.0", gate(201), ANA));
        AuditQueryService audits = new AuditQueryService(store);

        assertEquals(1, audits.listAudits(AuditFilter.of("Gate", "ana", "version.published"), PageRequest.of(1, 10)).items().size());
        assertEquals(0, audits.listAudits(AuditFilter.of("Other", null, null), PageRequest.of(1, 10)).items().size());
        assertEquals(0, audits.listAudits(AuditFilter.of(null, "bob", null), PageRequest.of(1, 10)).items().size());
        assertEquals(1, audits.listAudits(AuditFilter.of(null, null, null), PageRequest.of(2, 1)).items().size());
    }

    @Test
    void pagesSliceAndValidateRequests() {
        Page<Integer> page = Page.slice(List.of(1, 2, 3, 4, 5), new PageRequest(2, 2));

        assertEquals(List.of(3, 4), page.items());
        assertEquals(List.of("3", "4"), page.map(String::valueOf).items());
        assertEquals(List.of(), Page.slice(List.of(1), new PageRequest(5, 10)).items());
        assertEquals(20, PageRequest.of(null, null).pageSize());
        assertEquals("pageNumber starts at 1", assertThrows(InvalidRequestException.class, () -> new PageRequest(0, 1)).getMessage());
        assertThrows(InvalidRequestException.class, () -> new PageRequest(1, 101));
        assertThrows(InvalidRequestException.class, () -> new PageRequest(1, 0));
    }

    @Test
    void previewsCompileAndRunInPreviewMode() {
        PreviewPolicyUseCase preview = new PreviewPolicyService(compiler,
                new PolicyExecutor(request -> ApiResponse.failure("none"), ResultCache.disabled()),
                new ExecutionLimits(Duration.ofSeconds(1), 50));

        PreviewPolicyUseCase.Result result = preview.preview(new PreviewPolicyUseCase.Command(gate(201), Map.of()));

        assertEquals(List.of(), result.writeStates());
        assertEquals(201, assertInstanceOf(ExecutionOutcome.Responded.class, result.report().outcome()).status());
        assertFalse(result.report().trace().isEmpty());
        assertTrue(new PreviewPolicyUseCase.Command(gate(1), Map.of()).input().isEmpty());
    }

    private static PolicyDefinition gate(int status) {
        return new PolicyDefinition("done", List.of(StateDefinition.builder("done", "response").status(status).build()));
    }
}
