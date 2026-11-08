package br.com.lucaslima.enginerules.application.catalog.port.out;

import java.util.List;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;

public interface AppendAuditPort {

    void append(List<AuditRecord> records);
}
