package br.com.lucaslima.enginerules.app.admin;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import br.com.lucaslima.enginerules.adapter.expression.BuiltinExpressionLanguage;
import br.com.lucaslima.enginerules.adapter.grpc.GrpcDescriptors;
import br.com.lucaslima.enginerules.adapter.grpc.GrpcServiceContracts;
import br.com.lucaslima.enginerules.adapter.persistence.filesystem.FileSystemMetrics;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditQueries;
import br.com.lucaslima.enginerules.application.catalog.port.in.CreatePolicyUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.MoveStableVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyQueries;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.RemoveVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.service.AuditQueryService;
import br.com.lucaslima.enginerules.application.catalog.service.CatalogLookup;
import br.com.lucaslima.enginerules.application.catalog.service.CreatePolicyService;
import br.com.lucaslima.enginerules.application.catalog.service.MoveStableVersionService;
import br.com.lucaslima.enginerules.application.catalog.service.PolicyQueryService;
import br.com.lucaslima.enginerules.application.catalog.service.PublishVersionService;
import br.com.lucaslima.enginerules.application.catalog.service.RemoveVersionService;
import br.com.lucaslima.enginerules.application.rollout.port.in.CanaryUseCases;
import br.com.lucaslima.enginerules.application.rollout.port.in.EvaluateCanariesUseCase;
import br.com.lucaslima.enginerules.application.rollout.port.in.RolloutQueries;
import br.com.lucaslima.enginerules.application.rollout.port.in.ShadowUseCases;
import br.com.lucaslima.enginerules.application.rollout.service.CanaryEvaluationService;
import br.com.lucaslima.enginerules.application.rollout.service.CanaryService;
import br.com.lucaslima.enginerules.application.rollout.service.RolloutQueryService;
import br.com.lucaslima.enginerules.application.rollout.service.ShadowService;
import br.com.lucaslima.enginerules.domain.catalog.ChangeClassifier;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompiler;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryEvaluator;

@Configuration(proxyBeanMethods = false)
class AdminWiring {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PolicyCompiler policyCompiler(AdminProperties properties) {
        Map<String, GrpcDescriptors> descriptors = new LinkedHashMap<>();
        properties.grpcConnections().forEach((name, contract) ->
                descriptors.put(name, GrpcDescriptors.load(Path.of(contract.descriptorSet()))));
        return new PolicyCompiler(new BuiltinExpressionLanguage(), new GrpcServiceContracts(descriptors));
    }

    @Bean
    AdminStorage storage(AdminProperties properties, Clock clock) {
        return AdminStorage.from(properties, clock);
    }

    @Bean
    FileSystemMetrics metrics(AdminProperties properties) {
        return new FileSystemMetrics(Path.of(properties.storeDirectory()), 1);
    }

    @Bean
    CatalogLookup catalogLookup(AdminStorage storage) {
        return new CatalogLookup(storage.loadPolicies(), storage.loadVersions());
    }

    @Bean
    CreatePolicyUseCase createPolicyUseCase(AdminStorage storage, PolicyCompiler compiler, Clock clock) {
        return new CreatePolicyService(storage.loadPolicies(), storage.savePolicies(), storage.appendAudit(), compiler, clock);
    }

    @Bean
    PublishVersionUseCase publishVersionUseCase(CatalogLookup catalog, AdminStorage storage, PolicyCompiler compiler,
                                                Clock clock) {
        return new PublishVersionService(catalog, storage.savePolicies(), storage.appendAudit(), compiler,
                new ChangeClassifier(), clock);
    }

    @Bean
    MoveStableVersionUseCase moveStableVersionUseCase(CatalogLookup catalog, AdminStorage storage, Clock clock) {
        return new MoveStableVersionService(catalog, storage.savePolicies(), storage.appendAudit(), storage.rolloutGuard(),
                clock);
    }

    @Bean
    RemoveVersionUseCase removeVersionUseCase(CatalogLookup catalog, AdminStorage storage, Clock clock) {
        return new RemoveVersionService(catalog, storage.savePolicies(), storage.appendAudit(), storage.rolloutGuard(), clock);
    }

    @Bean
    PolicyQueries policyQueries(CatalogLookup catalog, AdminStorage storage) {
        return new PolicyQueryService(catalog, storage.listPolicies());
    }

    @Bean
    AuditQueries auditQueries(AdminStorage storage) {
        return new AuditQueryService(storage.listAudits());
    }

    @Bean
    CanaryUseCases canaryUseCases(CatalogLookup catalog, AdminStorage storage, AdminProperties properties, Clock clock) {
        return new CanaryService(catalog, storage.openCanaries(), storage.canaries(), new ChangeClassifier(),
                properties.canary().criteria().toDomain(), storage.appendAudit(), clock);
    }

    @Bean
    ShadowUseCases shadowUseCases(CatalogLookup catalog, AdminStorage storage, Clock clock) {
        return new ShadowService(catalog, storage.currentShadows(), storage.shadows(), storage.appendAudit(), clock);
    }

    @Bean
    RolloutQueries rolloutQueries(AdminStorage storage, FileSystemMetrics metrics, Clock clock) {
        return new RolloutQueryService(storage.canaries(), storage.shadows(), metrics, clock);
    }

    @Bean
    EvaluateCanariesUseCase evaluateCanariesUseCase(AdminStorage storage, FileSystemMetrics metrics, CatalogLookup catalog,
                                                    Clock clock) {
        return new CanaryEvaluationService(storage.canaries(), storage.shadows(), metrics, catalog, storage.savePolicies(),
                storage.appendAudit(), new CanaryEvaluator(), clock);
    }
}
