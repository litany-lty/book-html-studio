package studio.bookhtml.api;

import java.util.UUID;

public record ReadingWindowCommand(UUID sessionId, Long sequence) {}
