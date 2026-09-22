package studio.bookhtml.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.qwen-assist")
public class QwenAssistProperties {
    private boolean enabled = true;
    private String apiKey = "";
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String model = "qwen3.8-max";
    private int timeoutSeconds = 120;
    // U5：有界小任务并发（工程起点，需基准测试校准；不是供应商官方限额）。
    private boolean chunkedAssist = true;
    private int maxConcurrentRequests = 3;
    private int maxBackgroundRequests = 2;
    private int maxQueuedChunks = 24;
    private int maxPhysicalCallsPerPageAttempt = 8;
    private int reviewChunkChars = 2500;
    private int reviewChunkBlocksMin = 6;
    private int reviewChunkBlocksMax = 12;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public boolean isChunkedAssist() { return chunkedAssist; }
    public void setChunkedAssist(boolean chunkedAssist) { this.chunkedAssist = chunkedAssist; }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int maxConcurrentRequests) { this.maxConcurrentRequests = maxConcurrentRequests; }
    public int getMaxBackgroundRequests() { return maxBackgroundRequests; }
    public void setMaxBackgroundRequests(int maxBackgroundRequests) { this.maxBackgroundRequests = maxBackgroundRequests; }
    public int getMaxQueuedChunks() { return maxQueuedChunks; }
    public void setMaxQueuedChunks(int maxQueuedChunks) { this.maxQueuedChunks = maxQueuedChunks; }
    public int getMaxPhysicalCallsPerPageAttempt() { return maxPhysicalCallsPerPageAttempt; }
    public void setMaxPhysicalCallsPerPageAttempt(int maxPhysicalCallsPerPageAttempt) { this.maxPhysicalCallsPerPageAttempt = maxPhysicalCallsPerPageAttempt; }
    public int getReviewChunkChars() { return reviewChunkChars; }
    public void setReviewChunkChars(int reviewChunkChars) { this.reviewChunkChars = reviewChunkChars; }
    public int getReviewChunkBlocksMin() { return reviewChunkBlocksMin; }
    public void setReviewChunkBlocksMin(int reviewChunkBlocksMin) { this.reviewChunkBlocksMin = reviewChunkBlocksMin; }
    public int getReviewChunkBlocksMax() { return reviewChunkBlocksMax; }
    public void setReviewChunkBlocksMax(int reviewChunkBlocksMax) { this.reviewChunkBlocksMax = reviewChunkBlocksMax; }
}
