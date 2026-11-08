package br.com.lucaslima.enginerules.adapter.persistence.filesystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import br.com.lucaslima.enginerules.adapter.persistence.documents.PolicyDocuments;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyFilter;
import br.com.lucaslima.enginerules.application.catalog.port.out.ListPoliciesPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyVersionPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.port.out.SavePolicyPort;
import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.application.shared.PageRequest;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;

public final class FileSystemPolicyStore implements LoadPolicyPort, LoadPolicyVersionPort, SavePolicyPort, ListPoliciesPort {

    private static final String POLICY_FILE = "policy.json";

    private final Path policiesRoot;
    private final StoreFiles files;

    public FileSystemPolicyStore(Path root) {
        this.policiesRoot = root.resolve("policies");
        this.files = new StoreFiles(policiesRoot.resolve(".lock"));
    }

    @Override
    public Optional<Policy> loadPolicy(PolicyName name, ReadConsistency consistency) {
        return files.readObject(policyFile(name)).map(PolicyDocuments::toSnapshot).map(Policy::restore);
    }

    @Override
    public Optional<PolicyVersion> loadVersion(PolicyName name, SemanticVersion version) {
        return files.readObject(versionFile(name, version)).map(PolicyDocuments::toVersion);
    }

    @Override
    public void savePolicy(Policy policy, Optional<PolicyVersion> newVersion) {
        files.exclusively(() -> {
            requireExpectedRevision(policy);
            newVersion.ifPresent(this::writeVersionOnce);
            files.writeAtomically(policyFile(policy.name()),
                    PolicyDocuments.fromSnapshot(policy.snapshot(), policy.revision() + 1));
            return null;
        });
    }

    @Override
    public Page<Policy> listPolicies(PolicyFilter filter, PageRequest page) {
        List<Policy> matching = policyNames()
                .map(name -> loadPolicy(name, ReadConsistency.DIRECT))
                .flatMap(Optional::stream)
                .filter(policy -> filter.namePrefix().map(prefix -> policy.name().value().startsWith(prefix)).orElse(true))
                .filter(policy -> filter.ownerGroup().map(policy.ownerGroup()::equals).orElse(true))
                .filter(policy -> filter.withPendingCandidate()
                        .map(expected -> policy.pendingCandidate().isPresent() == expected).orElse(true))
                .sorted((left, right) -> left.name().compareTo(right.name()))
                .toList();
        return Page.slice(matching, page);
    }

    private void requireExpectedRevision(Policy policy) {
        long stored = files.readObject(policyFile(policy.name()))
                .map(tree -> ((Number) tree.get("revision")).longValue())
                .orElse(0L);
        boolean exists = Files.exists(policyFile(policy.name()));
        if ((exists && stored != policy.revision()) || (!exists && policy.revision() != 0)) {
            throw new CatalogException(CatalogErrorCode.CONCURRENT_MODIFICATION,
                    "policy " + policy.name() + " was modified concurrently; reload and retry");
        }
    }

    private void writeVersionOnce(PolicyVersion version) {
        Path file = versionFile(version.name(), version.version());
        Optional<PolicyVersion> existing = files.readObject(file).map(PolicyDocuments::toVersion);
        if (existing.isPresent() && !existing.get().hash().equals(version.hash())) {
            throw new CatalogException(CatalogErrorCode.CONCURRENT_MODIFICATION,
                    "version " + version.name() + "@" + version.version() + " already exists with different content");
        }
        if (existing.isEmpty()) {
            files.writeAtomically(file, PolicyDocuments.fromVersion(version));
        }
    }

    private Stream<PolicyName> policyNames() {
        return StoreFiles.children(policiesRoot).stream()
                .filter(Files::isDirectory)
                .map(directory -> PolicyName.of(directory.getFileName().toString()));
    }

    private Path policyFile(PolicyName name) {
        return policiesRoot.resolve(name.value()).resolve(POLICY_FILE);
    }

    private Path versionFile(PolicyName name, SemanticVersion version) {
        return policiesRoot.resolve(name.value()).resolve("versions").resolve(version + ".json");
    }
}
