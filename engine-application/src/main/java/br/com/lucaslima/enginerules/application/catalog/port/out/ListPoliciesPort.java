package br.com.lucaslima.enginerules.application.catalog.port.out;

import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyFilter;
import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.application.shared.PageRequest;
import br.com.lucaslima.enginerules.domain.catalog.Policy;

public interface ListPoliciesPort {

    Page<Policy> listPolicies(PolicyFilter filter, PageRequest page);
}
