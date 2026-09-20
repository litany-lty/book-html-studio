package studio.bookhtml.api;

import jakarta.validation.constraints.Pattern;

public record JobRequest(String pages,
                         @Pattern(regexp="local|qwen|paddle|paddle-aistudio|ppocr", message="provider 必须为 local、qwen、paddle、paddle-aistudio 或 ppocr") String provider,
                         @Pattern(regexp="auto|vertical|horizontal", message="layout 无效") String layout,
    boolean splitSpreads, boolean force, Boolean assist) {
    public boolean assistEnabled() { return assist == null || assist; }
    public String providerOrDefault() { return provider == null ? "paddle" : provider; }
}
