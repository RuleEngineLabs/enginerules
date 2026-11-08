package br.com.lucaslima.enginerules.adapter.persistence.filesystem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import br.com.lucaslima.enginerules.adapter.json.Json;
import br.com.lucaslima.enginerules.adapter.persistence.documents.PolicyDocuments;

final class StoreFiles {

    private final Path lockFile;

    StoreFiles(Path lockFile) {
        this.lockFile = lockFile;
    }

    Optional<Map<String, Object>> readObject(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(PolicyDocuments.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8))));
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read " + file, failure);
        }
    }

    void writeAtomically(Path file, Object tree) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            Files.writeString(temporary, Json.write(tree), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot write " + file, failure);
        }
    }

    static List<Path> children(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.sorted().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot list " + directory, failure);
        }
    }

    void appendLine(Path file, String line) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot append to " + file, failure);
        }
    }

    synchronized <T> T exclusively(Supplier<T> work) {
        try {
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return work.get();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot lock " + lockFile, failure);
        }
    }
}
