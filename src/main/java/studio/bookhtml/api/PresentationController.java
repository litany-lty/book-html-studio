package studio.bookhtml.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import studio.bookhtml.domain.PresentationOverride;
import studio.bookhtml.service.PresentationOverrideService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * U3：展示层人工覆盖入口。仅修改目录/展示角色，不算全文人工校对；
 * 恢复自动判断即删除对应覆盖。写操作沿用既有同源/写入保护。
 */
@RestController
@RequestMapping("/api/books/{id}/pages/{n}/presentation-overrides")
public class PresentationController {
    private final PresentationOverrideService overrides;

    public PresentationController(PresentationOverrideService overrides) {
        this.overrides = overrides;
    }

    @GetMapping public Map<String, Object> list(@PathVariable String id, @PathVariable int n) {
        PresentationOverride.Store store = overrides.list(id);
        List<PresentationOverride> page = store.overrides().stream()
                .filter(o -> o != null && o.pageNumber() == n).toList();
        return Map.of("overrideRevision", store.overrideRevision(), "overrides", page,
                "stale", overrides.stale(id));
    }

    @PutMapping public Map<String, Object> apply(@PathVariable String id, @PathVariable int n,
                                                 @Valid @RequestBody PresentationOverrideRequest request)
            throws IOException {
        PresentationOverride.Store updated = overrides.apply(id, n, request, "manual");
        return Map.of("overrideRevision", updated.overrideRevision(),
                "profileRevision", "recompute-on-read");
    }

    @GetMapping("/preview") public Map<String, Object> preview(@PathVariable String id,
                                                               @PathVariable int n,
                                                               @RequestParam String blockId) {
        PresentationOverrideService.ScopePreview preview = overrides.previewSameText(id, n, blockId);
        return Map.of("matchPages", preview.matchPages(), "pages", preview.pages(),
                "excludedChapterStarts", preview.excludedChapterStarts());
    }
}
