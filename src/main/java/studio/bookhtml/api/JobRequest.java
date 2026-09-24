package studio.bookhtml.api;

import jakarta.validation.constraints.Pattern;

public record JobRequest(String pages,
                         @Pattern(regexp="paddle-aistudio|ppocr|handwriting", message="provider 必须为 paddle-aistudio、ppocr 或 handwriting") String provider,
                         @Pattern(regexp="auto|vertical|horizontal", message="layout 无效") String layout,
    boolean splitSpreads, boolean force, Boolean assist) {
    public boolean assistEnabled() { return assist == null || assist; }
    public String providerOrDefault() { return provider == null ? "paddle-aistudio" : provider; }
}
