package br.com.lucaslima.enginerules.app.engine;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import br.com.lucaslima.enginerules.adapter.cache.BoundedShadowQueue;
import br.com.lucaslima.enginerules.adapter.cache.CachingPolicyCatalog;
import br.com.lucaslima.enginerules.adapter.cache.CachingRolloutLookup;
import br.com.lucaslima.enginerules.adapter.cache.InMemoryCompiledPolicyCache;
import br.com.lucaslima.enginerules.adapter.cache.InMemoryResultCache;
import br.com.lucaslima.enginerules.adapter.cache.StaticExecutionToggles;
import br.com.lucaslima.enginerules.adapter.expression.BuiltinExpressionLanguage;
import br.com.lucaslima.enginerules.adapter.grpc.GrpcApiGateway;
import br.com.lucaslima.enginerules.adapter.grpc.GrpcChannels;
import br.com.lucaslima.enginerules.adapter.grpc.GrpcConnection;
import br.com.lucaslima.enginerules.adapter.grpc.GrpcDescriptors;
import br.com.lucaslima.enginerules.adapter.grpc.GrpcServiceContracts;
import br.com.lucaslima.enginerules.adapter.http.HttpApiGateway;
import br.com.lucaslima.enginerules.adapter.http.HttpConnection;
import br.com.lucaslima.enginerules.adapter.persistence.filesystem.FileSystemMetrics;
import br.com.lucaslima.enginerules.application.catalog.service.CatalogLookup;
import br.com.lucaslima.enginerules.application.execution.ExecutionLimits;
import br.com.lucaslima.enginerules.application.execution.port.in.ExecutePolicyUseCase;
import br.com.lucaslima.enginerules.application.execution.port.in.PreviewPolicyUseCase;
import br.com.lucaslima.enginerules.application.execution.service.ExecutePolicyService;
import br.com.lucaslima.enginerules.application.execution.service.PreviewPolicyService;
import br.com.lucaslima.enginerules.application.execution.service.ShadowDispatcher;
import br.com.lucaslima.enginerules.application.execution.service.TrafficRouter;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompiler;
import br.com.lucaslima.enginerules.domain.execution.PolicyExecutor;
import br.com.lucaslima.enginerules.domain.execution.ProtocolRoutingGateway;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiGateway;
import br.com.lucaslima.enginerules.domain.execution.spi.Protocol;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowComparator;

@Configuration(proxyBeanMethods = false)
class EngineWiring {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RandomGenerator trafficRandom() {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return ThreadLocalRandom.current().nextLong();
            }
        };
    }

    @Bean
    GrpcSchemas grpcSchemas(EngineProperties properties) {
        Map<String, GrpcDescriptors> descriptors = new LinkedHashMap<>();
        properties.grpcConnections().forEach((name, connection) ->
                descriptors.put(name, GrpcDescriptors.load(Path.of(connection.descriptorSet()))));
        return new GrpcSchemas(descriptors);
    }

    @Bean
    PolicyCompiler policyCompiler(GrpcSchemas grpcSchemas) {
        return new PolicyCompiler(new BuiltinExpressionLanguage(), new GrpcServiceContracts(grpcSchemas.byConnection()));
    }

    @Bean
    GrpcApiGateway grpcApiGateway(EngineProperties properties, GrpcSchemas grpcSchemas) {
        Map<String, GrpcConnection> connections = new LinkedHashMap<>();
        properties.grpcConnections().forEach((name, connection) -> connections.put(name, new GrpcConnection(
                connection.target(), connection.plaintext(), Path.of(connection.descriptorSet()), connection.metadata())));
        return new GrpcApiGateway(connections, grpcSchemas.byConnection(), GrpcChannels::open);
    }

    @Bean
    PolicyExecutor policyExecutor(EngineProperties properties, GrpcApiGateway grpcApiGateway, Clock clock) {
        Map<String, HttpConnection> connections = new LinkedHashMap<>();
        properties.connections().forEach((name, connection) ->
                connections.put(name, new HttpConnection(connection.baseUrl(), connection.headers())));
        ApiGateway gateway = new ProtocolRoutingGateway(Map.of(
                Protocol.REST, new HttpApiGateway(connections, properties.connectTimeout()),
                Protocol.GRPC, grpcApiGateway));
        return new PolicyExecutor(gateway, new InMemoryResultCache(clock, properties.resultCacheRetention()));
    }

    @Bean
    EngineStorage storage(EngineProperties properties, Clock clock) {
        return EngineStorage.from(properties, clock);
    }

    @Bean
    CachingRolloutLookup rolloutLookup(EngineStorage storage, EngineProperties properties, Clock clock) {
        return new CachingRolloutLookup(storage.openCanaries(), storage.currentShadows(), clock,
                properties.rolloutRefreshInterval());
    }

    @Bean
    FileSystemMetrics metrics(EngineProperties properties) {
        return new FileSystemMetrics(Path.of(properties.storeDirectory()), properties.metrics().maxBufferedSamples());
    }

    @Bean
    BoundedShadowQueue shadowQueue(EngineProperties properties) {
        return new BoundedShadowQueue(properties.shadow().concurrency(), properties.shadow().queueCapacity());
    }

    @Bean
    InMemoryCompiledPolicyCache compiledPolicyCache(EngineProperties properties) {
        return new InMemoryCompiledPolicyCache(properties.compiledCacheMaxEntries());
    }

    @Bean
    CatalogLookup catalogLookup(EngineStorage storage, EngineProperties properties, Clock clock) {
        return new CatalogLookup(new CachingPolicyCatalog(storage.policies(), clock, properties.catalogRefreshInterval()),
                storage.versions());
    }

    @Bean
    ExecutionLimits executionLimits(EngineProperties properties) {
        return new ExecutionLimits(properties.execution().timeout(), properties.execution().stepBudget());
    }

    @Bean
    ShadowDispatcher shadowDispatcher(CachingRolloutLookup rollouts, BoundedShadowQueue queue, FileSystemMetrics metrics,
                                      CatalogLookup catalog, InMemoryCompiledPolicyCache compiledPolicies,
                                      PolicyCompiler compiler, PolicyExecutor executor, ExecutionLimits limits,
                                      EngineProperties properties, RandomGenerator trafficRandom, Clock clock) {
        return new ShadowDispatcher(rollouts, queue, metrics, catalog, compiledPolicies, compiler, executor,
                new ShadowComparator(properties.shadow().ignoredPaths()), limits, trafficRandom, clock);
    }

    @Bean
    ExecutePolicyUseCase executePolicyUseCase(CatalogLookup catalog, InMemoryCompiledPolicyCache compiledPolicies,
                                              EngineProperties properties, PolicyCompiler compiler, PolicyExecutor executor,
                                              ExecutionLimits limits, CachingRolloutLookup rollouts,
                                              ShadowDispatcher shadowDispatcher, FileSystemMetrics metrics,
                                              RandomGenerator trafficRandom, Clock clock) {
        return new ExecutePolicyService(catalog, compiledPolicies,
                new StaticExecutionToggles(properties.trace().enabled(), properties.trace().policies()),
                compiler, executor, limits, new TrafficRouter(rollouts, trafficRandom), shadowDispatcher, metrics, clock);
    }

    @Bean
    PreviewPolicyUseCase previewPolicyUseCase(PolicyCompiler compiler, PolicyExecutor executor, ExecutionLimits limits) {
        return new PreviewPolicyService(compiler, executor, limits);
    }

    record GrpcSchemas(Map<String, GrpcDescriptors> byConnection) {
        GrpcSchemas {
            byConnection = Map.copyOf(byConnection);
        }
    }
}
