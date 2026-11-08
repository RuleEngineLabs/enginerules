package br.com.lucaslima.enginerules.domain.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.RetryDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateKind;
import br.com.lucaslima.enginerules.domain.definition.Transition;
import br.com.lucaslima.enginerules.domain.execution.spi.CompiledExpression;
import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionLanguage;
import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionSyntaxException;
import br.com.lucaslima.enginerules.domain.execution.spi.FieldReference;
import br.com.lucaslima.enginerules.domain.execution.spi.Protocol;
import br.com.lucaslima.enginerules.domain.execution.spi.ServiceContracts;

public final class PolicyCompiler {

    public static final int MAX_RESULT_CACHE_TTL_SECONDS = 1800;
    public static final int MAX_PARALLEL_CONCURRENCY = 100;
    public static final int MAX_TIMEOUT_MS = 60_000;
    public static final int DEFAULT_TIMEOUT_MS = 5_000;

    private static final String INPUT = "input";
    private static final String RESULT = "result";
    private static final String ITEM = "item";
    private static final Set<String> RESERVED_CONTEXT_KEYS = Set.of(INPUT, RESULT, ITEM);
    private static final Set<String> API_RESULT_FIELDS = Set.of("status", "body", "headers", "error", "timeout");
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<StateKind> SUPPORTED_KINDS = Set.of(
            StateKind.EXECUTION, StateKind.API_CALL, StateKind.PARALLEL, StateKind.RESPONSE);
    private static final Set<StateKind> EACH_TARGET_KINDS = Set.of(StateKind.EXECUTION, StateKind.API_CALL);
    private static final Set<String> ROUTING_FIELDS = Set.of("transitions", "fallback", "contextKey");
    private static final Map<StateKind, Set<String>> KIND_FIELDS = Map.of(
            StateKind.EXECUTION, Set.of("transitions", "fallback", "contextKey", "map"),
            StateKind.API_CALL, Set.of("transitions", "fallback", "contextKey", "connection", "protocol", "service", "rpc",
                    "message", "readOnly", "method", "path", "pathParams", "query", "body", "timeoutMs", "retry", "ttlSeconds"),
            StateKind.PARALLEL, Set.of("transitions", "fallback", "contextKey", "over", "each", "maxConcurrency"),
            StateKind.RESPONSE, Set.of("status", "data"));
    private static final Set<String> GRPC_ONLY_FIELDS = Set.of("service", "rpc", "message", "readOnly");
    private static final Pattern GRPC_SERVICE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern GRPC_RPC = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern PATH_PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

    private final ExpressionLanguage expressionLanguage;
    private final ServiceContracts serviceContracts;

    public PolicyCompiler(ExpressionLanguage expressionLanguage) {
        this(expressionLanguage, ServiceContracts.unverified());
    }

    public PolicyCompiler(ExpressionLanguage expressionLanguage, ServiceContracts serviceContracts) {
        this.expressionLanguage = expressionLanguage;
        this.serviceContracts = serviceContracts;
    }

    public CompiledPolicy compile(PolicyDefinition definition) {
        return new Compilation(definition).run();
    }

    private final class Compilation {
        private final PolicyDefinition definition;
        private final List<CompilationViolation> violations = new ArrayList<>();
        private final Map<String, StateDefinition> statesById = new LinkedHashMap<>();
        private final Map<String, StateKind> kinds = new HashMap<>();
        private final Set<String> eachTargets = new HashSet<>();
        private final Map<String, Set<String>> objectContextFields = new HashMap<>();
        private final Set<String> listContextKeys = new HashSet<>();

        Compilation(PolicyDefinition definition) {
            this.definition = definition;
        }

        CompiledPolicy run() {
            indexStates();
            if (violations.isEmpty()) {
                collectContextShape();
            }
            Map<String, CompiledState> compiled = new LinkedHashMap<>();
            List<String> writeStates = new ArrayList<>();
            if (violations.isEmpty()) {
                statesById.values().forEach(state -> compileState(state).ifPresent(step -> {
                    compiled.put(step.id(), step);
                    if (step instanceof CompiledState.ApiCallStep call && call.writes()) {
                        writeStates.add(step.id());
                    }
                }));
            }
            if (!violations.isEmpty()) {
                throw new PolicyCompilationException(violations);
            }
            return new CompiledPolicy(definition.entry(), compiled, writeStates);
        }

        private void indexStates() {
            if (definition.states().isEmpty()) {
                violation("policy", "at least one state is required");
            }
            for (StateDefinition state : definition.states()) {
                if (isBlank(state.id())) {
                    violation("policy", "every state needs a non-blank id");
                    continue;
                }
                if (statesById.putIfAbsent(state.id(), state) != null) {
                    violation(state.id(), "duplicate state id");
                    continue;
                }
                Optional<StateKind> kind = StateKind.fromContractName(state.kind());
                if (kind.isEmpty() || !SUPPORTED_KINDS.contains(kind.get())) {
                    violation(state.id(), "unsupported kind '" + state.kind() + "' in the current phase");
                } else {
                    kinds.put(state.id(), kind.get());
                }
            }
            if (isBlank(definition.entry())) {
                violation("policy", "entry is required");
            } else if (!statesById.containsKey(definition.entry())) {
                violation("policy", "entry state '" + definition.entry() + "' does not exist");
            }
        }

        private void collectContextShape() {
            statesById.values().forEach(state -> {
                if (kinds.get(state.id()) == StateKind.PARALLEL && state.each() != null) {
                    eachTargets.add(state.each());
                }
            });
            statesById.values().forEach(state -> {
                String key = state.contextKey();
                if (key == null || eachTargets.contains(state.id())) {
                    return;
                }
                switch (kinds.get(state.id())) {
                    case EXECUTION -> objectContextFields.computeIfAbsent(key, ignored -> new HashSet<>())
                            .addAll(state.map() == null ? Set.of() : state.map().keySet());
                    case API_CALL -> objectContextFields.computeIfAbsent(key, ignored -> new HashSet<>())
                            .addAll(API_RESULT_FIELDS);
                    case PARALLEL -> listContextKeys.add(key);
                    default -> {
                    }
                }
            });
            listContextKeys.stream().filter(objectContextFields::containsKey).forEach(key ->
                    violation("policy", "contextKey '" + key + "' is written both as a list and as an object"));
        }

        private Optional<CompiledState> compileState(StateDefinition state) {
            StateKind kind = kinds.get(state.id());
            rejectFieldsOutsideKind(state, kind);
            boolean eachTarget = eachTargets.contains(state.id());
            if (eachTarget) {
                validateEachTarget(state, kind);
            } else if (kind != StateKind.RESPONSE) {
                validateRouting(state);
            }
            return switch (kind) {
                case EXECUTION -> Optional.of(compileExecution(state, eachTarget));
                case API_CALL -> Optional.ofNullable(compileApiCall(state, eachTarget));
                case PARALLEL -> Optional.of(compileParallel(state));
                case RESPONSE -> Optional.of(compileResponse(state));
                default -> Optional.empty();
            };
        }

        private void rejectFieldsOutsideKind(StateDefinition state, StateKind kind) {
            Set<String> allowed = KIND_FIELDS.get(kind);
            presentFields(state).stream()
                    .filter(field -> !allowed.contains(field))
                    .forEach(field -> violation(state.id(), "field '" + field + "' is not allowed for kind '"
                            + kind.contractName() + "'"));
        }

        private void validateEachTarget(StateDefinition state, StateKind kind) {
            if (!EACH_TARGET_KINDS.contains(kind)) {
                violation(state.id(), "a state referenced by 'each' must be of kind execution or apiCall");
            }
            presentFields(state).stream().filter(ROUTING_FIELDS::contains).forEach(field ->
                    violation(state.id(), "a state referenced by 'each' cannot declare '" + field + "'"));
            if (state.id().equals(definition.entry())) {
                violation(state.id(), "a state referenced by 'each' cannot be the entry");
            }
        }

        private void validateRouting(StateDefinition state) {
            if (isBlank(state.fallback())) {
                violation(state.id(), "fallback is required for non-terminal states");
            } else {
                requireRoutableTarget(state.id(), state.fallback(), "fallback");
            }
            state.transitionsOrEmpty().forEach(transition ->
                    requireRoutableTarget(state.id(), transition.to(), "transition target"));
            if (state.contextKey() != null && RESERVED_CONTEXT_KEYS.contains(state.contextKey())) {
                violation(state.id(), "contextKey '" + state.contextKey() + "' is reserved");
            }
            if (state.contextKey() != null && state.contextKey().isBlank()) {
                violation(state.id(), "contextKey cannot be blank");
            }
        }

        private void requireRoutableTarget(String stateId, String target, String role) {
            if (isBlank(target) || !statesById.containsKey(target)) {
                violation(stateId, role + " '" + target + "' does not exist");
            } else if (eachTargets.contains(target)) {
                violation(stateId, role + " '" + target + "' is reserved for parallel 'each' and cannot be routed to");
            }
        }

        private CompiledState compileExecution(StateDefinition state, boolean eachTarget) {
            Set<String> roots = eachTarget ? rootsWith(ITEM) : baseRoots();
            Map<String, ValueTemplate> output = new LinkedHashMap<>();
            if (state.map() != null) {
                state.map().forEach((field, source) ->
                        output.put(field, new ValueTemplate.Expression(expression(state.id(), "map." + field, source, roots))));
            }
            return new CompiledState.ExecutionStep(state.id(), new ValueTemplate.ObjectTemplate(output),
                    Optional.ofNullable(state.contextKey()),
                    transitions(state, baseRoots()), state.fallback());
        }

        private CompiledState compileApiCall(StateDefinition state, boolean eachTarget) {
            Set<String> roots = eachTarget ? rootsWith(ITEM) : baseRoots();
            if (isBlank(state.connection())) {
                violation(state.id(), "connection is required");
            }
            Optional<Protocol> protocol = Protocol.fromContractName(state.protocol());
            if (protocol.isEmpty()) {
                violation(state.id(), "protocol must be 'rest' or 'grpc'");
                return null;
            }
            return protocol.get() == Protocol.GRPC
                    ? compileGrpcCall(state, roots)
                    : compileRestCall(state, roots);
        }

        private CompiledState compileRestCall(StateDefinition state, Set<String> roots) {
            rejectProtocolFields(state, GRPC_ONLY_FIELDS, "rest");
            String method = state.method() == null ? null : state.method().toUpperCase();
            if (method == null || !HTTP_METHODS.contains(method)) {
                violation(state.id(), "method must be one of " + HTTP_METHODS);
            }
            Map<String, GuardedExpression> pathParams = compilePath(state, roots);
            Map<String, GuardedExpression> query = new LinkedHashMap<>();
            if (state.query() != null) {
                state.query().forEach((name, source) -> query.put(name, expression(state.id(), "query." + name, source, roots)));
            }
            if (state.body() != null && "GET".equals(method)) {
                violation(state.id(), "GET calls cannot declare a body");
            }
            Optional<ValueTemplate> body = Optional.ofNullable(state.body())
                    .map(tree -> template(state.id(), "body", tree, roots));
            boolean readOnly = "GET".equals(method);
            return new CompiledState.ApiCallStep(state.id(), Protocol.REST, state.connection(), method, state.path(),
                    pathParams, query, body, timeout(state), retry(state), resultCacheTtl(state, readOnly), readOnly,
                    Optional.ofNullable(state.contextKey()),
                    transitions(state, rootsWith(RESULT)), state.fallback());
        }

        private CompiledState compileGrpcCall(StateDefinition state, Set<String> roots) {
            rejectProtocolFields(state, Set.of("method", "path", "pathParams", "query", "body"), "grpc");
            if (isBlank(state.service()) || !GRPC_SERVICE.matcher(state.service()).matches()) {
                violation(state.id(), "service must be a fully qualified gRPC service name");
            }
            if (isBlank(state.rpc()) || !GRPC_RPC.matcher(state.rpc()).matches()) {
                violation(state.id(), "rpc must be a gRPC method name");
            }
            if (state.message() != null && !(state.message() instanceof Map<?, ?>)) {
                violation(state.id(), "message must be an object");
            }
            if (!isBlank(state.connection()) && !isBlank(state.service()) && !isBlank(state.rpc())) {
                Set<String> fields = state.message() instanceof Map<?, ?> map
                        ? map.keySet().stream().map(String::valueOf).collect(Collectors.toSet())
                        : Set.of();
                serviceContracts.verifyGrpcCall(state.connection(), state.service(), state.rpc(), fields)
                        .ifPresent(problem -> violation(state.id(), problem));
            }
            Optional<ValueTemplate> message = Optional.ofNullable(state.message())
                    .map(tree -> template(state.id(), "message", tree, roots));
            boolean readOnly = Boolean.TRUE.equals(state.readOnly());
            return new CompiledState.ApiCallStep(state.id(), Protocol.GRPC, state.connection(), state.rpc(), state.service(),
                    Map.of(), Map.of(), message, timeout(state), retry(state), resultCacheTtl(state, readOnly), readOnly,
                    Optional.ofNullable(state.contextKey()),
                    transitions(state, rootsWith(RESULT)), state.fallback());
        }

        private void rejectProtocolFields(StateDefinition state, Set<String> forbidden, String protocol) {
            presentFields(state).stream().filter(forbidden::contains).forEach(field ->
                    violation(state.id(), "field '" + field + "' is not allowed for protocol '" + protocol + "'"));
        }

        private Map<String, GuardedExpression> compilePath(StateDefinition state, Set<String> roots) {
            Map<String, GuardedExpression> pathParams = new LinkedHashMap<>();
            if (isBlank(state.path()) || !state.path().startsWith("/")) {
                violation(state.id(), "path is required and must start with '/'");
                return pathParams;
            }
            Set<String> placeholders = new LinkedHashSet<>();
            Matcher matcher = PATH_PLACEHOLDER.matcher(state.path());
            while (matcher.find()) {
                placeholders.add(matcher.group(1));
            }
            Map<String, String> declared = state.pathParams() == null ? Map.of() : state.pathParams();
            placeholders.stream().filter(name -> !declared.containsKey(name)).forEach(name ->
                    violation(state.id(), "path placeholder '{" + name + "}' has no entry in pathParams"));
            declared.forEach((name, source) -> {
                if (!placeholders.contains(name)) {
                    violation(state.id(), "pathParams '" + name + "' is not used in path");
                }
                pathParams.put(name, expression(state.id(), "pathParams." + name, source, roots));
            });
            return pathParams;
        }

        private Duration timeout(StateDefinition state) {
            int timeoutMs = state.timeoutMs() == null ? DEFAULT_TIMEOUT_MS : state.timeoutMs();
            if (timeoutMs < 1 || timeoutMs > MAX_TIMEOUT_MS) {
                violation(state.id(), "timeoutMs must be between 1 and " + MAX_TIMEOUT_MS);
            }
            return Duration.ofMillis(Math.max(timeoutMs, 1));
        }

        private RetryRule retry(StateDefinition state) {
            RetryDefinition retry = state.retry();
            if (retry == null) {
                return RetryRule.NO_RETRY;
            }
            int attempts = retry.maxAttempts() != null ? retry.maxAttempts() : retry.backoffMs().size() + 1;
            if (attempts < 1 || attempts > RetryRule.MAX_ATTEMPTS_CEILING) {
                violation(state.id(), "retry.maxAttempts must be between 1 and " + RetryRule.MAX_ATTEMPTS_CEILING);
            }
            if (retry.backoffMs().stream().anyMatch(gap -> gap < 0)) {
                violation(state.id(), "retry.backoffMs cannot contain negative values");
            }
            return new RetryRule(Math.max(1, Math.min(attempts, RetryRule.MAX_ATTEMPTS_CEILING)), retry.backoffMs());
        }

        private Optional<Duration> resultCacheTtl(StateDefinition state, boolean readOnly) {
            if (state.ttlSeconds() == null) {
                return Optional.empty();
            }
            if (!readOnly) {
                violation(state.id(), "ttlSeconds is only allowed for GET or readOnly calls");
            }
            if (state.ttlSeconds() < 1 || state.ttlSeconds() > MAX_RESULT_CACHE_TTL_SECONDS) {
                violation(state.id(), "ttlSeconds must be between 1 and " + MAX_RESULT_CACHE_TTL_SECONDS);
            }
            return Optional.of(Duration.ofSeconds(state.ttlSeconds()));
        }

        private CompiledState compileParallel(StateDefinition state) {
            if (isBlank(state.each()) || !statesById.containsKey(state.each())) {
                violation(state.id(), "each '" + state.each() + "' does not exist");
            }
            Integer concurrency = state.maxConcurrency();
            if (concurrency == null || concurrency < 1 || concurrency > MAX_PARALLEL_CONCURRENCY) {
                violation(state.id(), "maxConcurrency is required and must be between 1 and " + MAX_PARALLEL_CONCURRENCY);
            }
            GuardedExpression over = null;
            if (isBlank(state.over())) {
                violation(state.id(), "over is required");
            } else {
                over = expression(state.id(), "over", state.over(), baseRoots());
            }
            return new CompiledState.ParallelStep(state.id(), over, state.each(),
                    concurrency == null ? 1 : concurrency,
                    Optional.ofNullable(state.contextKey()), transitions(state, baseRoots()), state.fallback());
        }

        private CompiledState compileResponse(StateDefinition state) {
            if (state.status() == null || state.status() < 100 || state.status() > 599) {
                violation(state.id(), "status is required and must be a valid HTTP status");
            }
            Optional<ValueTemplate> data = Optional.ofNullable(state.data())
                    .map(tree -> template(state.id(), "data", tree, baseRoots()));
            return new CompiledState.ResponseStep(state.id(), state.status() == null ? 500 : state.status(), data);
        }

        private List<CompiledTransition> transitions(StateDefinition state, Set<String> roots) {
            List<CompiledTransition> compiled = new ArrayList<>();
            List<Transition> transitions = state.transitionsOrEmpty();
            for (int index = 0; index < transitions.size(); index++) {
                Transition transition = transitions.get(index);
                compiled.add(new CompiledTransition(
                        expression(state.id(), "transitions[" + index + "].when", transition.when(), roots),
                        transition.to()));
            }
            return compiled;
        }

        private ValueTemplate template(String stateId, String location, Object tree, Set<String> roots) {
            if (tree instanceof String source) {
                return new ValueTemplate.Expression(expression(stateId, location, source, roots));
            }
            if (tree instanceof Map<?, ?> map) {
                Map<String, ValueTemplate> fields = new LinkedHashMap<>();
                map.forEach((key, value) -> fields.put(String.valueOf(key),
                        template(stateId, location + "." + key, value, roots)));
                return new ValueTemplate.ObjectTemplate(fields);
            }
            if (tree instanceof List<?> list) {
                List<ValueTemplate> items = new ArrayList<>();
                for (int index = 0; index < list.size(); index++) {
                    items.add(template(stateId, location + "[" + index + "]", list.get(index), roots));
                }
                return new ValueTemplate.ArrayTemplate(items);
            }
            return new ValueTemplate.Literal(tree);
        }

        private GuardedExpression expression(String stateId, String location, String source, Set<String> roots) {
            String where = stateId + " " + location;
            if (isBlank(source)) {
                violation(where, "expression cannot be blank");
                return null;
            }
            CompiledExpression compiled;
            try {
                compiled = expressionLanguage.compile(source);
            } catch (ExpressionSyntaxException syntaxError) {
                violation(where, syntaxError.getMessage());
                return null;
            }
            List<FieldReference> guarded = new ArrayList<>();
            for (FieldReference reference : compiled.references()) {
                checkReference(where, reference, roots).ifPresent(guarded::add);
            }
            return new GuardedExpression(compiled, guarded);
        }

        private Optional<FieldReference> checkReference(String where, FieldReference reference, Set<String> roots) {
            String root = reference.root();
            if (!roots.contains(root)) {
                violation(where, "unknown variable '" + root + "'");
                return Optional.empty();
            }
            if (RESULT.equals(root) && reference.field() != null && !API_RESULT_FIELDS.contains(reference.field())) {
                violation(where, "'result' has no field '" + reference.field() + "'");
            }
            if (listContextKeys.contains(root)) {
                if (reference.field() != null) {
                    violation(where, "'" + root + "' is a list and has no field '" + reference.field() + "'");
                }
                return Optional.of(FieldReference.rootOnly(root));
            }
            if (objectContextFields.containsKey(root)) {
                if (reference.field() != null && !objectContextFields.get(root).contains(reference.field())) {
                    violation(where, "field '" + reference.path() + "' is never written by any state");
                }
                return Optional.of(reference);
            }
            return Optional.empty();
        }

        private Set<String> baseRoots() {
            Set<String> roots = new HashSet<>();
            roots.add(INPUT);
            roots.addAll(objectContextFields.keySet());
            roots.addAll(listContextKeys);
            return roots;
        }

        private Set<String> rootsWith(String extra) {
            Set<String> roots = baseRoots();
            roots.add(extra);
            return roots;
        }

        private Set<String> presentFields(StateDefinition state) {
            Set<String> present = new LinkedHashSet<>();
            addIfPresent(present, "transitions", state.transitions());
            addIfPresent(present, "fallback", state.fallback());
            addIfPresent(present, "contextKey", state.contextKey());
            addIfPresent(present, "map", state.map());
            addIfPresent(present, "connection", state.connection());
            addIfPresent(present, "protocol", state.protocol());
            addIfPresent(present, "service", state.service());
            addIfPresent(present, "rpc", state.rpc());
            addIfPresent(present, "message", state.message());
            addIfPresent(present, "readOnly", state.readOnly());
            addIfPresent(present, "method", state.method());
            addIfPresent(present, "path", state.path());
            addIfPresent(present, "pathParams", state.pathParams());
            addIfPresent(present, "query", state.query());
            addIfPresent(present, "body", state.body());
            addIfPresent(present, "timeoutMs", state.timeoutMs());
            addIfPresent(present, "retry", state.retry());
            addIfPresent(present, "ttlSeconds", state.ttlSeconds());
            addIfPresent(present, "over", state.over());
            addIfPresent(present, "each", state.each());
            addIfPresent(present, "maxConcurrency", state.maxConcurrency());
            addIfPresent(present, "status", state.status());
            addIfPresent(present, "data", state.data());
            return present;
        }

        private void addIfPresent(Set<String> present, String field, Object value) {
            if (value != null) {
                present.add(field);
            }
        }

        private void violation(String location, String message) {
            violations.add(new CompilationViolation(location, message));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
