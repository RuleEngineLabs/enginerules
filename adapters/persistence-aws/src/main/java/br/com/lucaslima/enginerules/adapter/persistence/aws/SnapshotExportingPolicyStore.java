package br.com.lucaslima.enginerules.adapter.persistence.aws;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Optional;

import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyVersionPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.SavePolicyPort;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import software.amazon.awssdk.core.exception.SdkException;

public final class SnapshotExportingPolicyStore implements SavePolicyPort {

    private static final Logger LOG = System.getLogger(SnapshotExportingPolicyStore.class.getName());

    private final SavePolicyPort delegate;
    private final LoadPolicyVersionPort versions;
    private final S3StableSnapshots snapshots;

    public SnapshotExportingPolicyStore(SavePolicyPort delegate, LoadPolicyVersionPort versions, S3StableSnapshots snapshots) {
        this.delegate = delegate;
        this.versions = versions;
        this.snapshots = snapshots;
    }

    @Override
    public void savePolicy(Policy policy, Optional<PolicyVersion> newVersion) {
        delegate.savePolicy(policy, newVersion);
        try {
            PolicyVersion stable = newVersion.filter(version -> version.version().equals(policy.stable()))
                    .or(() -> versions.loadVersion(policy.name(), policy.stable()))
                    .orElseThrow(() -> new IllegalStateException("stable version of " + policy.name() + " is missing"));
            snapshots.export(policy, policy.revision() + 1, stable);
        } catch (SdkException failure) {
            LOG.log(Level.WARNING, "could not export the stable snapshot of " + policy.name()
                    + "; the next admin write retries it", failure);
        }
    }
}
