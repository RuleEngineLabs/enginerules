package br.com.lucaslima.enginerules.application.catalog.port.in;

import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.application.shared.PageRequest;

public interface AuditQueries {

    Page<AuditRecord> listAudits(AuditFilter filter, PageRequest page);
}
