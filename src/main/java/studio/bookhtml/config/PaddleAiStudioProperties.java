package studio.bookhtml.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.paddle-aistudio")
public record PaddleAiStudioProperties(String accessToken, String jobUrl, String model,
                                       int requestTimeoutSeconds, int totalTimeoutSeconds,
                                       int pollIntervalSeconds) {
    public PaddleAiStudioProperties {
        if (accessToken == null) accessToken = "";
        if (jobUrl == null || jobUrl.isBlank()) jobUrl = "https://paddleocr.aistudio-app.com/api/v2/ocr/jobs";
        if (model == null || model.isBlank()) model = "PaddleOCR-VL-1.6";
        if (requestTimeoutSeconds <= 0) requestTimeoutSeconds = 60;
        if (totalTimeoutSeconds <= 0) totalTimeoutSeconds = 180;
        if (pollIntervalSeconds <= 0) pollIntervalSeconds = 5;
    }

    public String getAccessToken() { return accessToken; }
    public String getJobUrl() { return jobUrl; }
    public String getModel() { return model; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public int getTotalTimeoutSeconds() { return totalTimeoutSeconds; }
    public int getPollIntervalSeconds() { return pollIntervalSeconds; }

    @Override
    public String toString() {
        return "PaddleAiStudioProperties[configuration=[REDACTED]]";
    }
}
