package studio.bookhtml.api;

import org.springframework.web.bind.annotation.*;
import studio.bookhtml.service.ReadingWindowService;

import java.util.UUID;

@RestController
@RequestMapping("/api/books/{id}/reading-window")
public class ReadingWindowController {
    private final ReadingWindowService windows;
    public ReadingWindowController(ReadingWindowService windows) { this.windows = windows; }

    @PostMapping public ReadingWindowResponse update(@PathVariable String id, @RequestBody ReadingWindowRequest request) {
        return windows.update(id, request);
    }
    @GetMapping public ReadingWindowResponse get(@PathVariable String id, @RequestParam UUID sessionId) {
        return windows.get(id, sessionId);
    }
    @PostMapping("/stop") public ReadingWindowResponse stop(@PathVariable String id, @RequestBody ReadingWindowCommand command) {
        return windows.stop(id, command);
    }
}
