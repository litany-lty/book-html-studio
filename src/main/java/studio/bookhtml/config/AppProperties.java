package studio.bookhtml.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.nio.file.Path;

@ConfigurationProperties("app")
public record AppProperties(Path dataDir, long maxUploadMb, int maxPages, int maxImageWidth,
                            String tesseractCommand, String dashscopeApiKey, String qwenModel,
                            String qwenBaseUrl, int qwenTimeoutSeconds, String minimaxApiKey, String minimaxModel,
                            String minimaxBaseUrl, int minimaxTimeoutSeconds, boolean minimaxAssistEnabled,
                            String baiduOcrApiKey, String baiduOcrSecretKey, String paddleModel,
                            String paddleJobUrl, int paddleRequestTimeoutSeconds,
                            int paddleTotalTimeoutSeconds, int paddlePollIntervalSeconds) {
    public AppProperties(Path dataDir, long maxUploadMb, int maxPages, int maxImageWidth,
                         String tesseractCommand, String dashscopeApiKey, String qwenModel,
                         String qwenBaseUrl, int qwenTimeoutSeconds, String minimaxApiKey, String minimaxModel,
                         String minimaxBaseUrl, int minimaxTimeoutSeconds, boolean minimaxAssistEnabled) {
        this(dataDir,maxUploadMb,maxPages,maxImageWidth,tesseractCommand,dashscopeApiKey,qwenModel,qwenBaseUrl,
                qwenTimeoutSeconds,minimaxApiKey,minimaxModel,minimaxBaseUrl,minimaxTimeoutSeconds,
                minimaxAssistEnabled,"","","PaddleOCR-VL-1.6",
                "https://aip.baidubce.com/rest/2.0/brain/online/v2/paddle-vl-parser/task",60,180,5);
    }
    @ConstructorBinding public AppProperties {
        if (dataDir == null) dataDir = Path.of("./data");
        if (maxUploadMb <= 0) maxUploadMb = 300;
        if (maxPages <= 0) maxPages = 5000;
        if (maxImageWidth <= 0) maxImageWidth = 2400;
        if (tesseractCommand == null || tesseractCommand.isBlank()) tesseractCommand = "tesseract";
        if (dashscopeApiKey == null) dashscopeApiKey = "";
        if (qwenModel == null || qwenModel.isBlank()) qwenModel = "qwen3.5-ocr";
        if (qwenBaseUrl == null || qwenBaseUrl.isBlank()) qwenBaseUrl = "https://dashscope.aliyuncs.com/api/v1";
        if (qwenTimeoutSeconds <= 0) qwenTimeoutSeconds = 60;
        if (minimaxApiKey == null) minimaxApiKey = "";
        if (minimaxModel == null || minimaxModel.isBlank()) minimaxModel = "MiniMax-M3";
        if (minimaxBaseUrl == null || minimaxBaseUrl.isBlank()) minimaxBaseUrl = "https://api.minimax.cn/v1";
        if (minimaxTimeoutSeconds <= 0) minimaxTimeoutSeconds = 60;
        if (baiduOcrApiKey == null) baiduOcrApiKey = "";
        if (baiduOcrSecretKey == null) baiduOcrSecretKey = "";
        if (paddleModel == null || paddleModel.isBlank()) paddleModel = "PaddleOCR-VL-1.6";
        if (paddleJobUrl == null || paddleJobUrl.isBlank()) paddleJobUrl = "https://aip.baidubce.com/rest/2.0/brain/online/v2/paddle-vl-parser/task";
        if (paddleRequestTimeoutSeconds <= 0) paddleRequestTimeoutSeconds = 60;
        if (paddleTotalTimeoutSeconds <= 0) paddleTotalTimeoutSeconds = 180;
        if (paddlePollIntervalSeconds <= 0) paddlePollIntervalSeconds = 5;
    }
    /** Configuration values can contain credentials or signed endpoint URLs. */
    @Override public String toString() {
        return "AppProperties[configuration=REDACTED]";
    }

}
