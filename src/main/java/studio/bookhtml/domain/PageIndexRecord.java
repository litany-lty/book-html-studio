package studio.bookhtml.domain;

import java.util.List;
import java.util.UUID;

/**
 * Sharded persistent page index record.
 * Holds searchable text segments and edge evidence without bloating with full-resolution images or debug records.
 */
public record PageIndexRecord(
        String bookId,
        String pdfSourceHash,
        int pageNumber,
        int revision,
        UUID commitId,
        long appliedSourceSeq,
        String status,
        boolean processed,
        boolean reviewed,
        int unresolvedCount,
        double width,
        double height,
        String title,
        List<TextSegment> textSegments,
        List<EdgeEvidence> edgeEvidences
) {
    public record TextSegment(String blockId,String text,String type,double[] bbox,String original) {
        public TextSegment(String blockId,String text,String type,double[] bbox) { this(blockId,text,type,bbox,text); }
    }
    public record EdgeEvidence(String layoutGroup, String edgeZone, String positionBucket, String normalizedText) {}
}
