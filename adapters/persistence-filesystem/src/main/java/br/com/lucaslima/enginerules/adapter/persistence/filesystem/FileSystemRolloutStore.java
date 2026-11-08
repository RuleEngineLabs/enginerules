package br.com.lucaslima.enginerules.adapter.persistence.filesystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import br.com.lucaslima.enginerules.adapter.persistence.documents.RolloutDocuments;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.port.out.RolloutGuardPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.CanaryStorePort;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadCurrentShadowPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadOpenCanaryPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowStorePort;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStatus;

public final class FileSystemRolloutStore implements LoadOpenCanaryPort, CanaryStorePort, LoadCurrentShadowPort,
        ShadowStorePort, RolloutGuardPort {

    private final Path canariesRoot;
    private final Path shadowsRoot;
    private final StoreFiles canaryFiles;
    private final StoreFiles shadowFiles;
    private final Clock clock;

    public FileSystemRolloutStore(Path root, Clock clock) {
        this.canariesRoot = root.resolve("canaries");
        this.shadowsRoot = root.resolve("shadows");
        this.canaryFiles = new StoreFiles(canariesRoot.resolve(".lock"));
        this.shadowFiles = new StoreFiles(shadowsRoot.resolve(".lock"));
        this.clock = clock;
    }

    @Override
    public Optional<Canary> findOpen(PolicyName name, ReadConsistency consistency) {
        return canaryHistory(name).stream().filter(canary -> canary.status().isOpen()).findFirst();
    }

    @Override
    public Optional<Canary> findCanary(PolicyName name, String id) {
        return canaryFiles.readObject(canariesRoot.resolve(name.value()).resolve(id + ".json"))
                .map(RolloutDocuments::toCanary).map(Canary::restore);
    }

    @Override
    public List<Canary> canaryHistory(PolicyName name) {
        return documents(canariesRoot.resolve(name.value()), canaryFiles)
                .map(RolloutDocuments::toCanary).map(Canary::restore)
                .sorted(Comparator.comparing(Canary::startedAt).reversed())
                .toList();
    }

    @Override
    public List<Canary> listOpen() {
        return policyDirectories(canariesRoot)
                .flatMap(name -> findOpen(name, ReadConsistency.DIRECT).stream())
                .toList();
    }

    @Override
    public void save(Canary canary) {
        canaryFiles.exclusively(() -> {
            canaryFiles.writeAtomically(canariesRoot.resolve(canary.policy().value()).resolve(canary.id() + ".json"),
                    RolloutDocuments.fromCanary(canary.snapshot(), canary.revision() + 1));
            return null;
        });
    }

    @Override
    public Optional<Shadow> findCurrent(PolicyName name, ReadConsistency consistency) {
        return shadowHistory(name).stream()
                .filter(shadow -> shadow.snapshot().status() == ShadowStatus.ACTIVE || shadow.snapshot().status() == ShadowStatus.PAUSED)
                .findFirst();
    }

    @Override
    public Optional<Shadow> findShadow(PolicyName name, String id) {
        return shadowFiles.readObject(shadowsRoot.resolve(name.value()).resolve(id + ".json"))
                .map(RolloutDocuments::toShadow).map(Shadow::restore);
    }

    @Override
    public void save(Shadow shadow) {
        shadowFiles.exclusively(() -> {
            shadowFiles.writeAtomically(shadowsRoot.resolve(shadow.policy().value()).resolve(shadow.id() + ".json"),
                    RolloutDocuments.fromShadow(shadow.snapshot(), shadow.revision() + 1));
            return null;
        });
    }

    @Override
    public boolean hasOpenCanary(PolicyName name) {
        return findOpen(name, ReadConsistency.DIRECT).isPresent();
    }

    @Override
    public boolean isVersionInUse(PolicyName name, SemanticVersion version) {
        boolean canaryCandidate = findOpen(name, ReadConsistency.DIRECT)
                .map(canary -> canary.candidate().equals(version)).orElse(false);
        boolean shadowTarget = findCurrent(name, ReadConsistency.DIRECT)
                .filter(shadow -> shadow.isLiveAt(clock.instant()))
                .map(shadow -> shadow.target().equals(version)).orElse(false);
        return canaryCandidate || shadowTarget;
    }

    @Override
    public List<Shadow> shadowHistory(PolicyName name) {
        return documents(shadowsRoot.resolve(name.value()), shadowFiles)
                .map(RolloutDocuments::toShadow).map(Shadow::restore)
                .sorted(Comparator.comparing((Shadow shadow) -> shadow.snapshot().startedAt()).reversed())
                .toList();
    }

    private static Stream<Map<String, Object>> documents(Path directory, StoreFiles files) {
        return StoreFiles.children(directory).stream()
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .flatMap(path -> files.readObject(path).stream());
    }

    private static Stream<PolicyName> policyDirectories(Path root) {
        return StoreFiles.children(root).stream()
                .filter(Files::isDirectory)
                .map(path -> PolicyName.of(path.getFileName().toString()));
    }
}
