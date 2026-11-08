package br.com.lucaslima.enginerules.domain.rollout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.execution.ExecutionErrorCode;
import br.com.lucaslima.enginerules.domain.execution.ExecutionOutcome;
import br.com.lucaslima.enginerules.domain.execution.IoBinding;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.ResultCache;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ComparisonResult;
import br.com.lucaslima.enginerules.domain.rollout.shadow.IoRecording;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowComparator;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStatus;
import br.com.lucaslima.enginerules.domain.rollout.shadow.VirtualIo;
import br.com.lucaslima.enginerules.domain.rollout.shadow.VirtualRoute;

class ShadowTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final ShadowComparator COMPARATOR = new ShadowComparator(List.of("data.generatedAt"));

    @Test
    void pausesResumesAndExpires() {
        Shadow shadow = shadow(100);

        shadow.pause(NOW);
        assertFalse(shadow.copies(Optional.of("a"), NOW, new Random()));
        shadow.resume(NOW);
        assertTrue(shadow.copies(Optional.of("a"), NOW, new Random()));
        assertEquals(ShadowStatus.EXPIRED, shadow.statusAt(NOW.plus(Duration.ofHours(25))));
    }

    @Test
    void comparesIgnoringConfiguredPathsAndTreatingAbsentAsNull() {
        Map<String, Object> real = Map.of("limit", 1000, "generatedAt", "12:00", "items", List.of(Map.of("id", 1, "at", "x")));
        Map<String, Object> copy = Map.of("limit", 1000.0, "generatedAt", "12:01", "items", List.of(Map.of("id", 1, "at", "y")));
        HashMap<String, Object> withNull = new HashMap<>(real);
        withNull.put("note", null);

        assertEquals(ComparisonResult.EQUAL, COMPARATOR.compare(responded(real), responded(copy), false, List.of("data.items[*].at")));
        assertEquals(ComparisonResult.EQUAL, COMPARATOR.compare(responded(real), responded(withNull), false, List.of()));
        assertEquals(ComparisonResult.DIVERGENT_DATA, COMPARATOR.compare(responded(real), responded(copy), false, List.of()));
    }

    @Test
    void classifiesOutcomeDifferences() {
        ExecutionOutcome ok = responded(Map.of());
        ExecutionOutcome failed = new ExecutionOutcome.Failed(ExecutionErrorCode.MISSING_CONTEXT_FIELD, "x");

        assertEquals(ComparisonResult.SHADOW_ERROR_ONLY, COMPARATOR.compare(ok, failed, false, List.of()));
        assertEquals(ComparisonResult.REAL_ERROR_ONLY, COMPARATOR.compare(failed, ok, false, List.of()));
        assertEquals(ComparisonResult.DIVERGENT_OUTCOME,
                COMPARATOR.compare(ok, new ExecutionOutcome.Responded("other", 200, Map.of()), false, List.of()));
        assertEquals(ComparisonResult.NOT_COMPARABLE, COMPARATOR.compare(ok, ok, true, List.of()));
    }

    @Test
    void replaysRecordedIoAndFallsBackToVirtualRoutes() {
        ApiRequest recordedCall = ApiRequest.rest("bureau", "POST", "/scores", Map.of(), Map.of(), Map.of("doc", "1"), Duration.ofSeconds(1));
        ApiRequest newCall = ApiRequest.rest("bureau", "GET", "/limits/{doc}", Map.of("doc", "1"), Map.of(), null, Duration.ofSeconds(1));
        ApiRequest unknownCall = ApiRequest.rest("crm", "GET", "/x", Map.of(), Map.of(), null, Duration.ofSeconds(1));
        IoRecording recording = new IoRecording(new IoBinding(request -> ApiResponse.of(201, Map.of("score", 900), Map.of()),
                ResultCache.disabled()));
        recording.binding().apiGateway().call(recordedCall);

        VirtualIo virtualIo = new VirtualIo(recording.recorded(),
                List.of(new VirtualRoute("bureau", "GET", "/limits/{doc}", 200, Map.of("limit", 5))));

        assertEquals(Integer.valueOf(201), virtualIo.call(recordedCall).status());
        assertEquals(Integer.valueOf(200), virtualIo.call(newCall).status());
        assertFalse(virtualIo.missed());
        assertEquals(VirtualIo.MISS, virtualIo.call(unknownCall).error());
        assertTrue(virtualIo.missed());
    }

    private static ExecutionOutcome responded(Map<String, Object> data) {
        return new ExecutionOutcome.Responded("approved", 200, data);
    }

    private static Shadow shadow(int percentage) {
        return Shadow.start("s-1", PolicyName.of("CreditLimit"), SemanticVersion.parse("1.1.0"), percentage, null,
                List.of(), List.of(), "ana", NOW);
    }
}
