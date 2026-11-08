package br.com.lucaslima.enginerules.application.catalog.port.out;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditFilter;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.application.shared.PageRequest;

public interface ListAuditsPort {

    Page<AuditRecord> listAudits(AuditFilter filter, PageRequest page);
}
