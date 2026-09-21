package studio.bookhtml.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * J01/10.1：决策应用配置。初值均为本项目建议初值，不是官方服务限制或性能保证。
 * mode 默认 OFF；monetaryBudget 为空时禁止真实外呼；calibration 初始 UNVALIDATED。
 */
@Component
@ConfigurationProperties(prefix = "book.decision")
public class DecisionProperties {
    private String mode = "OFF";
    private String provider = "TYPESAFE";
    private String apiKey = "";
    private String model = "";
    private int maxConcurrentJobs = 1;
    private int maxQueueEntries = 32;
    private int queueWaitTimeoutSeconds = 30;
    private int jobDeadlineSeconds = 180;
    private int jevAttemptDeadlineSeconds = 20;
    private int visionAttemptDeadlineSeconds = 60;
    private int maxCandidatesPerIssue = 6;
    private int maxQuestionsPerRequest = 8;
    private int maxRequestBytes = 32768;
    private int maxResponseBytes = 65536;
    private int maxFreshVisionCallsPerIssue = 1;
    private int maxAutomaticRegionsPerPage = 3;
    private int maxPhysicalAttemptsPerLogicalCall = 1;
    private String thresholdProfile = "pilot-default-v1";
    private String calibrationStatus = "UNVALIDATED";
    private boolean allowCloudData = false;
    private Long monetaryBudgetMinor = null;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode == null ? "OFF" : mode; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider == null ? "TYPESAFE" : provider; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model == null ? "" : model; }
    public int getMaxConcurrentJobs() { return maxConcurrentJobs; }
    public void setMaxConcurrentJobs(int v) { this.maxConcurrentJobs = v; }
    public int getMaxQueueEntries() { return maxQueueEntries; }
    public void setMaxQueueEntries(int v) { this.maxQueueEntries = v; }
    public int getQueueWaitTimeoutSeconds() { return queueWaitTimeoutSeconds; }
    public void setQueueWaitTimeoutSeconds(int v) { this.queueWaitTimeoutSeconds = v; }
    public int getJobDeadlineSeconds() { return jobDeadlineSeconds; }
    public void setJobDeadlineSeconds(int v) { this.jobDeadlineSeconds = v; }
    public int getJevAttemptDeadlineSeconds() { return jevAttemptDeadlineSeconds; }
    public void setJevAttemptDeadlineSeconds(int v) { this.jevAttemptDeadlineSeconds = v; }
    public int getVisionAttemptDeadlineSeconds() { return visionAttemptDeadlineSeconds; }
    public void setVisionAttemptDeadlineSeconds(int v) { this.visionAttemptDeadlineSeconds = v; }
    public int getMaxCandidatesPerIssue() { return maxCandidatesPerIssue; }
    public void setMaxCandidatesPerIssue(int v) { this.maxCandidatesPerIssue = v; }
    public int getMaxQuestionsPerRequest() { return maxQuestionsPerRequest; }
    public void setMaxQuestionsPerRequest(int v) { this.maxQuestionsPerRequest = v; }
    public int getMaxRequestBytes() { return maxRequestBytes; }
    public void setMaxRequestBytes(int v) { this.maxRequestBytes = v; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int v) { this.maxResponseBytes = v; }
    public int getMaxFreshVisionCallsPerIssue() { return maxFreshVisionCallsPerIssue; }
    public void setMaxFreshVisionCallsPerIssue(int v) { this.maxFreshVisionCallsPerIssue = v; }
    public int getMaxAutomaticRegionsPerPage() { return maxAutomaticRegionsPerPage; }
    public void setMaxAutomaticRegionsPerPage(int v) { this.maxAutomaticRegionsPerPage = v; }
    public int getMaxPhysicalAttemptsPerLogicalCall() { return maxPhysicalAttemptsPerLogicalCall; }
    public void setMaxPhysicalAttemptsPerLogicalCall(int v) { this.maxPhysicalAttemptsPerLogicalCall = v; }
    public String getThresholdProfile() { return thresholdProfile; }
    public void setThresholdProfile(String v) { this.thresholdProfile = v == null ? "pilot-default-v1" : v; }
    public String getCalibrationStatus() { return calibrationStatus; }
    public void setCalibrationStatus(String v) { this.calibrationStatus = v == null ? "UNVALIDATED" : v; }
    public boolean isAllowCloudData() { return allowCloudData; }
    public void setAllowCloudData(boolean v) { this.allowCloudData = v; }
    public Long getMonetaryBudgetMinor() { return monetaryBudgetMinor; }
    public void setMonetaryBudgetMinor(Long v) { this.monetaryBudgetMinor = v; }

    /** 外呼资格：任一不满足即返回禁用原因码，否则返回 null。密钥缺失、模型为空、外发未授权、预算未设一律禁呼。 */
    public String availabilityReason() {
        if (!"SHADOW".equalsIgnoreCase(mode) && !"ASSIST".equalsIgnoreCase(mode)) return "DECISION_OFF";
        if (apiKey == null || apiKey.isBlank()) return "MISSING_API_KEY";
        if (model == null || model.isBlank()) return "MISSING_MODEL";
        if (!allowCloudData) return "DATA_EGRESS_NOT_AUTHORIZED";
        if (monetaryBudgetMinor == null) return "BUDGET_NOT_SET";
        return null;
    }
}
