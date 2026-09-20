package studio.bookhtml.api;

import jakarta.validation.constraints.NotNull;
import studio.bookhtml.domain.Block;
import java.util.List;

public record PageUpdateRequest(@NotNull List<Block> blocks, boolean reviewed, Integer revision) {
    public PageUpdateRequest(@NotNull List<Block> blocks, boolean reviewed) { this(blocks, reviewed, null); }
}
