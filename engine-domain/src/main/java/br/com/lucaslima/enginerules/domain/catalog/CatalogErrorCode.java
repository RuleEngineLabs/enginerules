package br.com.lucaslima.enginerules.domain.catalog;

public enum CatalogErrorCode {
    INVALID_POLICY_NAME("invalid_policy_name"),
    INVALID_VERSION("invalid_version"),
    POLICY_NOT_FOUND("policy_not_found"),
    VERSION_NOT_FOUND("version_not_found"),
    POLICY_ALREADY_EXISTS("policy_already_exists"),
    BASED_ON_REQUIRED("based_on_required"),
    BASED_ON_NOT_STABLE("based_on_not_stable"),
    PENDING_CANDIDATE_EXISTS("pending_candidate_exists"),
    PENDING_CANDIDATE_NOT_EXECUTABLE("pending_candidate_not_executable"),
    VERSION_REMOVED("version_removed"),
    VERSION_IS_STABLE("version_is_stable"),
    ALREADY_STABLE("already_stable"),
    NOT_OWNER("not_owner"),
    CONSUMER_NOT_ALLOWED("consumer_not_allowed"),
    CANARY_IN_PROGRESS("canary_in_progress"),
    VERSION_IN_USE("version_in_use"),
    CONCURRENT_MODIFICATION("concurrent_modification");

    private final String code;

    CatalogErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
