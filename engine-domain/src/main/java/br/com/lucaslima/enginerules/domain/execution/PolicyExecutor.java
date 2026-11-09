package br.com.lucaslima.enginerules.domain.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import br.com.lucaslima.enginerules.domain.execution.CompiledState.ApiCallStep;
import br.com.lucaslima.enginerules.domain.execution.CompiledState.ExecutionStep;
import br.com.lucaslima.enginerules.domain.execution.CompiledState.ParallelStep;
import br.com.lucaslima.enginerules.domain.execution.CompiledState.ResponseStep;
import br.com.lucaslima.enginerules.domain.execution.CompiledState.Routed;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiGateway;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionEvaluationException;
import br.com.lucaslima.enginerules.domain.execution.spi.Protocol;
import br.com.lucaslima.enginerules.domain.execution.spi.ResultCache;

public final class PolicyExecutor {

    private static final String INPUT = "input";
    private static final String RESULT = "result";
    private static final String ITEM = "item";

    private final IoBinding defaultIo;

    public PolicyExecutor(ApiGateway apiGateway, ResultCache resultCache) {
        this.defaultIo = new IoBinding(apiGateway, resultCache);
    }

    public ExecutionReport execute(CompiledPolicy policy, Map<String, Object> input, ExecutionOptions options) {
        return execute(policy, input, options, defaultIo);
    }

    public ExecutionReport execute(CompiledPolicy policy, Map<String, Object> input, ExecutionOptions options, IoBinding io) {
        return new Run(policy, input, options, io).execute();
    }

    public IoBinding defaultIo() {
        return defaultIo;
    }

    private final class Run {
        private final CompiledPolicy policy;
        private final ExecutionOptions options;
        private final IoBinding io;
        private final Map<String, Object> context = new HashMap<>();
        private final List<TraceEntry> trace = new ArrayList<>();
        private final long startedAt = System.nanoTime();
        private final long deadline;
        private int steps;

        Run(CompiledPolicy policy, Map<String, Object> input, ExecutionOptions options, IoBinding io) {
            this.policy = policy;
            this.options = options;
            this.io = io;
            this.deadline = startedAt + options.timeout().toNanos();
            context.put(INPUT, input == null ? Map.of() : input);
        }

        ExecutionReport execute() {
            try {
                return finish(walk());
            } catch (ExecutionAbort abort) {
                return finish(new ExecutionOutcome.Failed(abort.code, abort.getMessage()));
            } catch (MissingContextFieldException missing) {
                return finish(new ExecutionOutcome.Failed(ExecutionErrorCode.MISSING_CONTEXT_FIELD, missing.getMessage()));
            } catch (ExpressionEvaluationException evaluation) {
                return finish(new ExecutionOutcome.Failed(ExecutionErrorCode.EXPRESSION_ERROR, evaluation.getMessage()));
            }
        }

        private ExecutionOutcome walk() {
            String current = policy.entry();
            while (true) {
                guardBudgets();
                CompiledState state = policy.state(current);
                long stepStartedAt = System.nanoTime();
                switch (state) {
                    case ResponseStep response -> {
                        Object data = response.data().map(template -> template.render(context)).orElse(null);
                        record(TraceBuilder.of(response, stepStartedAt));
                        return new ExecutionOutcome.Responded(response.id(), response.status(), data);
                    }
                    case ExecutionStep execution -> current = runExecution(execution, stepStartedAt);
                    case ApiCallStep call -> {
                        if (blockedInPreview(call)) {
                            record(TraceBuilder.of(call, stepStartedAt));
                            return new ExecutionOutcome.PreviewBlocked(call.id());
                        }
                        current = runApiCall(call, stepStartedAt);
                    }
                    case ParallelStep parallel -> {
                        CompiledState each = policy.state(parallel.each());
                        if (each instanceof ApiCallStep eachCall && blockedInPreview(eachCall)) {
                            record(TraceBuilder.of(parallel, stepStartedAt));
                            return new ExecutionOutcome.PreviewBlocked(eachCall.id());
                        }
                        current = runParallel(parallel, each, stepStartedAt);
                    }
                }
            }
        }

        private String runExecution(ExecutionStep execution, long stepStartedAt) {
            Object output = execution.output().render(context);
            execution.contextKey().ifPresent(key -> context.put(key, output));
            String next = route(execution, context);
            record(TraceBuilder.of(execution, stepStartedAt).next(next));
            return next;
        }

        private String runApiCall(ApiCallStep call, long stepStartedAt) {
            TraceBuilder traceBuilder = TraceBuilder.of(call, stepStartedAt);
            ApiResponse response = invoke(call, context, traceBuilder);
            Map<String, Object> result = response.asContextValue();
            call.contextKey().ifPresent(key -> context.put(key, result));
            String next = route(call, with(context, RESULT, result));
            record(traceBuilder.next(next));
            return next;
        }

        private String runParallel(ParallelStep parallel, CompiledState each, long stepStartedAt) {
            Object source = parallel.over().evaluate(context);
            if (!(source instanceof List<?> items) || items.stream().anyMatch(Objects::isNull)) {
                throw new ExecutionAbort(ExecutionErrorCode.INVALID_PARALLEL_SOURCE,
                        "state '" + parallel.id() + "': 'over' must resolve to a list without null items");
            }
            List<Object> outputs = runEach(items, each, parallel.maxConcurrency());
            parallel.contextKey().ifPresent(key -> context.put(key, outputs));
            String next = route(parallel, context);
            record(TraceBuilder.of(parallel, stepStartedAt).next(next).failedItems(failedItems(each, outputs)));
            return next;
        }

        private List<Object> runEach(List<?> items, CompiledState each, int maxConcurrency) {
            if (items.isEmpty()) {
                return List.of();
            }
            Semaphore permits = new Semaphore(maxConcurrency);
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                List<Future<Object>> futures = new ArrayList<>(items.size());
                for (Object item : items) {
                    Map<String, Object> itemContext = with(context, ITEM, item);
                    futures.add(executor.submit(() -> {
                        permits.acquire();
                        try {
                            return runEachItem(each, itemContext);
                        } finally {
                            permits.release();
                        }
                    }));
                }
                List<Object> outputs = new ArrayList<>(items.size());
                for (Future<Object> future : futures) {
                    outputs.add(await(future));
                }
                return outputs;
            } finally {
                executor.shutdownNow();
            }
        }

        private Object runEachItem(CompiledState each, Map<String, Object> itemContext) {
            return switch (each) {
                case ExecutionStep execution -> execution.output().render(itemContext);
                case ApiCallStep call -> invoke(call, itemContext, TraceBuilder.discarding()).asContextValue();
                default -> throw new IllegalStateException("unsupported each target " + each.id());
            };
        }

        private Object await(Future<Object> future) {
            try {
                return future.get(remainingNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                throw new ExecutionAbort(ExecutionErrorCode.DEADLINE_EXCEEDED, "execution deadline exceeded");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ExecutionAbort(ExecutionErrorCode.INTERRUPTED, "execution interrupted");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(failure.getCause());
            }
        }

        private Map<String, List<Integer>> failedItems(CompiledState each, List<Object> outputs) {
            if (!(each instanceof ApiCallStep)) {
                return Map.of();
            }
            Map<String, List<Integer>> groups = new LinkedHashMap<>();
            for (int index = 0; index < outputs.size(); index++) {
                int itemIndex = index;
                failureSignature(outputs.get(index))
                        .ifPresent(signature -> groups.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(itemIndex));
            }
            return groups;
        }

        private Optional<String> failureSignature(Object output) {
            if (!(output instanceof Map<?, ?> result)) {
                return Optional.empty();
            }
            if (Boolean.TRUE.equals(result.get("timeout"))) {
                return Optional.of("timeout");
            }
            if (result.get("error") != null) {
                return Optional.of("error");
            }
            if (result.get("status") instanceof Integer status && status >= 400) {
                return Optional.of("status=" + status);
            }
            return Optional.empty();
        }

        private ApiResponse invoke(ApiCallStep call, Map<String, Object> scope, TraceBuilder traceBuilder) {
            ApiRequest request = request(call, scope);
            Optional<ApiResponse> cached = call.resultCacheTtl()
                    .flatMap(ttl -> io.resultCache().find(request.signature(), ttl));
            if (cached.isPresent()) {
                traceBuilder.fromCache();
                return cached.get();
            }
            ApiResponse response = callWithRetry(call, request, traceBuilder);
            if (call.resultCacheTtl().isPresent() && isCacheable(response)) {
                io.resultCache().store(request.signature(), response);
            }
            return response;
        }

        private ApiResponse callWithRetry(ApiCallStep call, ApiRequest request, TraceBuilder traceBuilder) {
            int attempts = 0;
            while (true) {
                guardDeadline();
                long attemptStartedAt = System.nanoTime();
                ApiResponse response = io.apiGateway().call(request);
                attempts++;
                traceBuilder.attempt(response, attemptStartedAt);
                if (!call.retry().shouldRetry(response, attempts)) {
                    return response;
                }
                pause(call.retry().backoffAfter(attempts));
            }
        }

        private ApiRequest request(ApiCallStep call, Map<String, Object> scope) {
            Object body = call.body().map(template -> template.render(scope)).orElse(null);
            Duration timeout = call.timeout().compareTo(Duration.ofNanos(remainingNanos())) < 0
                    ? call.timeout() : Duration.ofNanos(Math.max(remainingNanos(), 1));
            if (call.protocol() == Protocol.GRPC) {
                return ApiRequest.grpc(call.connection(), call.pathTemplate(), call.method(), body, timeout);
            }
            Map<String, String> pathParams = new LinkedHashMap<>();
            call.pathParams().forEach((name, expression) -> {
                Object value = expression.evaluate(scope);
                if (value == null) {
                    throw new ExecutionAbort(ExecutionErrorCode.INVALID_REQUEST_PARAMETER,
                            "state '" + call.id() + "': path parameter '" + name + "' resolved to null");
                }
                pathParams.put(name, String.valueOf(value));
            });
            Map<String, String> query = new LinkedHashMap<>();
            call.query().forEach((name, expression) -> {
                Object value = expression.evaluate(scope);
                if (value != null) {
                    query.put(name, String.valueOf(value));
                }
            });
            return ApiRequest.rest(call.connection(), call.method(), call.pathTemplate(), pathParams, query, body, timeout);
        }

        private String route(Routed state, Map<String, Object> scope) {
            for (CompiledTransition transition : state.transitions()) {
                Object decision = transition.when().evaluate(scope);
                if (Boolean.TRUE.equals(decision)) {
                    return transition.to();
                }
                if (decision != null && !(decision instanceof Boolean)) {
                    throw new ExpressionEvaluationException("state '" + state.id() + "': transition condition '"
                            + transition.when().expression().source() + "' must be boolean");
                }
            }
            return state.fallback();
        }

        private boolean blockedInPreview(ApiCallStep call) {
            return options.mode() == ExecutionMode.PREVIEW && call.writes();
        }

        private void guardBudgets() {
            if (++steps > options.stepBudget()) {
                throw new ExecutionAbort(ExecutionErrorCode.STEP_BUDGET_EXCEEDED,
                        "step budget of " + options.stepBudget() + " exceeded");
            }
            guardDeadline();
        }

        private void guardDeadline() {
            if (remainingNanos() <= 0) {
                throw new ExecutionAbort(ExecutionErrorCode.DEADLINE_EXCEEDED, "execution deadline exceeded");
            }
        }

        private void pause(Duration backoff) {
            if (backoff.isZero()) {
                return;
            }
            if (backoff.toNanos() >= remainingNanos()) {
                throw new ExecutionAbort(ExecutionErrorCode.DEADLINE_EXCEEDED, "retry backoff exceeds execution deadline");
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ExecutionAbort(ExecutionErrorCode.INTERRUPTED, "execution interrupted");
            }
        }

        private long remainingNanos() {
            return deadline - System.nanoTime();
        }

        private void record(TraceBuilder entry) {
            if (options.traceEnabled()) {
                trace.add(entry.build());
            }
        }

        private ExecutionReport finish(ExecutionOutcome outcome) {
            return new ExecutionReport(outcome, trace, steps, Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    private static boolean isCacheable(ApiResponse response) {
        return !response.timeout() && response.error() == null && response.status() != null && response.status() < 500;
    }

    private static Map<String, Object> with(Map<String, Object> scope, String name, Object value) {
        Map<String, Object> extended = new HashMap<>(scope);
        extended.put(name, value);
        return extended;
    }

    private static final class ExecutionAbort extends RuntimeException {
        private final ExecutionErrorCode code;

        ExecutionAbort(ExecutionErrorCode code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class TraceBuilder {
        private final CompiledState state;
        private final long startedAt;
        private final List<TraceEntry.Attempt> attempts = new ArrayList<>();
        private String next;
        private boolean fromCache;
        private Map<String, List<Integer>> failedItems = Map.of();

        private TraceBuilder(CompiledState state, long startedAt) {
            this.state = state;
            this.startedAt = startedAt;
        }

        static TraceBuilder of(CompiledState state, long startedAt) {
            return new TraceBuilder(state, startedAt);
        }

        static TraceBuilder discarding() {
            return new TraceBuilder(null, 0);
        }

        TraceBuilder next(String value) {
            this.next = value;
            return this;
        }

        TraceBuilder failedItems(Map<String, List<Integer>> value) {
            this.failedItems = value;
            return this;
        }

        void fromCache() {
            this.fromCache = true;
        }

        void attempt(ApiResponse response, long attemptStartedAt) {
            if (state != null) {
                attempts.add(new TraceEntry.Attempt(response.status(), response.error(), response.timeout(),
                        micros(System.nanoTime() - attemptStartedAt)));
            }
        }

        TraceEntry build() {
            return new TraceEntry(state.id(), state.kind().contractName(), next, micros(System.nanoTime() - startedAt),
                    attempts, fromCache, failedItems);
        }

        private static long micros(long nanos) {
            return TimeUnit.NANOSECONDS.toMicros(nanos);
        }
    }
}
