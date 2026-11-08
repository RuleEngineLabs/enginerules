package br.com.lucaslima.enginerules.adapter.web.admin;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditFilter;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditQueries;
import br.com.lucaslima.enginerules.application.shared.PageRequest;

@RestController
public class AuditController {

    private final AuditQueries audits;

    public AuditController(AuditQueries audits) {
        this.audits = audits;
    }

    @GetMapping("/audits")
    public Map<String, Object> list(
            @RequestParam(name = "policy", required = false) String policy,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "pageNumber", required = false) Integer pageNumber,
            @RequestParam(name = "pageSize", required = false) Integer pageSize) {
        return AdminResponses.page(audits.listAudits(AuditFilter.of(policy, actor, action),
                PageRequest.of(pageNumber, pageSize)).map(AdminResponses::audit));
    }
}
