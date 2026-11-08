package br.com.lucaslima.enginerules.application.catalog.service;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditFilter;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditQueries;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.out.ListAuditsPort;
import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.application.shared.PageRequest;

public final class AuditQueryService implements AuditQueries {

    private final ListAuditsPort listAudits;

    public AuditQueryService(ListAuditsPort listAudits) {
        this.listAudits = listAudits;
    }

    @Override
    public Page<AuditRecord> listAudits(AuditFilter filter, PageRequest page) {
        return listAudits.listAudits(filter, page);
    }
}
