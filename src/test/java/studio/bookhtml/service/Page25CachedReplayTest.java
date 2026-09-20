package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Page;

import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Page25CachedReplayTest {
    @Test void replaysSavedQwenAndMiniMaxResponsesWithoutNetwork()throws Exception{
        Path originalPath=Path.of("data/books/c65fc6ba-c378-455c-8aa8-9c68be9e2b6a/pages/25.original.json");Path probePath=Path.of("verification/probe-minimax-layout.json");Assumptions.assumeTrue(Files.isRegularFile(originalPath)&&Files.isRegularFile(probePath),"私有真实响应不存在时跳过缓存 replay");
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();Page original=json.readValue(originalPath.toFile(),Page.class);JsonNode probe=json.readTree(probePath.toFile());String content=probe.at("/result/choices/0/message/content").asText();assertFalse(content.isBlank());
        MiniMaxVisionClient miniMax=new MiniMaxVisionClient(TestConfigs.config(Path.of("target/replay-unused"),"unused","unused"),json,r->{throw new AssertionError("cached replay 不得访问网络");});List<Block>merged=miniMax.merge(MiniMaxVisionClient.stripFence(content),original.sourceRecords());List<Block>normalized=new VerticalLayoutNormalizer().normalize(merged,original.sourceRecords());TraditionalConverter converter=new TraditionalConverter();List<Block>simplified=normalized.stream().map(b->new Block(b.id(),b.type(),b.order(),b.bbox(),b.writingMode(),b.original(),converter.toSimplified(b.original()),b.confidence(),b.uncertain(),b.reviewed(),b.headingLevel(),b.source(),b.sourceIds(),b.suggestion(),b.sourceRect())).toList();
        Set<String>expected=new HashSet<>(original.sourceRecords().stream().map(Block::id).toList());List<String>actual=simplified.stream().flatMap(b->b.sourceIds().stream()).toList();assertEquals(expected,new HashSet<>(actual));assertEquals(actual.size(),new HashSet<>(actual).size());String reading=simplified.stream().map(Block::original).reduce("",(a,b)->a+"|"+b);assertTrue(reading.indexOf("或重要")<reading.indexOf("財運也罷"));assertTrue(reading.indexOf("下面我們")<reading.indexOf("越滾越"));
        List<String>warnings=List.of("cached-replay：复用已保存的 Qwen OCR 与 MiniMax 响应，未调用外网", "自动识别结果仍需人工校对");Page replayed=new Page(original.pageNumber(),original.width(),original.height(),"READY","cached-replay:qwen+minimax",simplified,warnings,false,null,original.sourceRecords());Path output=Path.of("verification/page25-replayed.json");Files.createDirectories(output.getParent());json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),replayed);assertTrue(Files.size(output)>0);
    }
}
