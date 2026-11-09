package br.com.lucaslima.enginerules.adapter.persistence.aws;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyVersionPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import software.amazon.awssdk.core.exception.SdkException;

public final class SnapshotFallbackCatalog implements LoadPolicyPort, LoadPolicyVersionPort {

    private static final Logger LOG = System.getLogger(SnapshotFallbackCatalog.class.getName());

    private final LoadPolicyPort policies;
    private final LoadPolicyVersionPort versions;
    private final S3StableSnapshots snapshots;
    private final AtomicBoolean usingFallback = new AtomicBoolean();

    public SnapshotFallbackCatalog(LoadPolicyPort policies, LoadPolicyVersionPort versions, S3StableSnapshots snapshots) {
        this.policies = policies;
        this.versions = versions;
        this.snapshots = snapshots;
    }

    public boolean usingFallback() {
        return usingFallback.get();
    }

    @Override
    public Optional<Policy> loadPolicy(PolicyName name, ReadConsistency consistency) {
        return read(() -> policies.loadPolicy(name, consistency),
                () -> snapshots.read(name).map(S3StableSnapshots.StableSnapshot::policy));
    }

    @Override
    public Optional<PolicyVersion> loadVersion(PolicyName name, SemanticVersion version) {
        return read(() -> versions.loadVersion(name, version),
                () -> snapshots.read(name).map(S3StableSnapshots.StableSnapshot::stable)
                        .filter(stable -> stable.version().equals(version)));
    }

    private <T> Optional<T> read(Supplier<Optional<T>> primary, Supplier<Optional<T>> fallback) {
        try {
            Optional<T> result = primary.get();
            if (usingFallback.compareAndSet(true, false)) {
                LOG.log(Level.INFO, "DynamoDB is reachable again; left the snapshot fallback mode");
            }
            return result;
        } catch (SdkException unavailable) {
            if (usingFallback.compareAndSet(false, true)) {
                LOG.log(Level.WARNING, "DynamoDB is unreachable; serving STABLE versions from the S3 snapshot", unavailable);
            }
            return fallback.get();
        }
    }
}
