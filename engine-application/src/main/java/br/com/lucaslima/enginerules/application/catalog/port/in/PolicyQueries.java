package br.com.lucaslima.enginerules.application.catalog.port.in;

import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.application.shared.PageRequest;

public interface PolicyQueries {

    PolicySummary findPolicy(String name);

    PolicyVersionDetails findVersion(String name, String version);

    Page<PolicySummary> listPolicies(PolicyFilter filter, PageRequest page);
}
