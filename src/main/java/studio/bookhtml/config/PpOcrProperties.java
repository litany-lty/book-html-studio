package studio.bookhtml.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ppocr")
public record PpOcrProperties(String url, int requestTimeoutSeconds) {
    public PpOcrProperties {
        if (url == null || url.isBlank()) url = "https://aip.baidubce.com/rest/2.0/ocr/v1/pp_ocrv5";
        if (requestTimeoutSeconds <= 0) requestTimeoutSeconds = 60;
    }

    public String getUrl() { return url; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }

    @Override
    public String toString() {
        return "PpOcrProperties[url=REDACTED, requestTimeoutSeconds=" + requestTimeoutSeconds + "]";
    }
}
