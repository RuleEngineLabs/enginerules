package br.com.lucaslima.enginerules.adapter.persistence.filesystem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import br.com.lucaslima.enginerules.adapter.json.Json;
import br.com.lucaslima.enginerules.adapter.persistence.documents.AuditDocuments;
import br.com.lucaslima.enginerules.adapter.persistence.documents.PolicyDocuments;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditFilter;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.out.AppendAuditPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ListAuditsPort;
import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.application.shared.PageRequest;

public final class FileSystemAuditLog implements AppendAuditPort, ListAuditsPort {

    private final Path logFile;
    private final StoreFiles files;

    public FileSystemAuditLog(Path root) {
        this.logFile = root.resolve("audit").resolve("audit.jsonl");
        this.files = new StoreFiles(logFile.resolveSibling(".lock"));
    }

    @Override
    public void append(List<AuditRecord> records) {
        files.exclusively(() -> {
            records.forEach(record -> files.appendLine(logFile, Json.write(AuditDocuments.fromRecord(record))));
            return null;
        });
    }

    @Override
    public Page<AuditRecord> listAudits(AuditFilter filter, PageRequest page) {
        List<AuditRecord> matching = new ArrayList<>(readAll().stream().filter(filter::matches).toList());
        Collections.reverse(matching);
        return Page.slice(matching, page);
    }

    private List<AuditRecord> readAll() {
        if (!Files.isRegularFile(logFile)) {
            return List.of();
        }
        try {
            return Files.readAllLines(logFile, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .map(line -> AuditDocuments.toRecord(PolicyDocuments.object(Json.parse(line))))
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read " + logFile, failure);
        }
    }


}
