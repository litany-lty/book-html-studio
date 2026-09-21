package studio.bookhtml.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

@Service
public class NativeTextExtractor {
    private final TraditionalConverter converter;
    public NativeTextExtractor(TraditionalConverter converter) { this.converter = converter; }

    public Optional<List<Block>> extract(Path pdf, int pageNumber, String requestedLayout) throws IOException {
        try (PDDocument document = PdfService.loadPdf(pdf)) {
            Collector c = new Collector(); c.setStartPage(pageNumber); c.setEndPage(pageNumber); c.getText(document);
            if (!trustworthy(c.glyphs)) return Optional.empty();
            String layout = chooseLayout(c.glyphs, requestedLayout);
            return Optional.of(toBlocks(c.glyphs, layout, converter));
        }
    }
    static boolean trustworthy(List<Glyph> glyphs) {
        if (glyphs.size() < 8) return false;
        String text = glyphs.stream().map(Glyph::text).reduce("", String::concat);
        long visible = text.codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
        long bad = text.codePoints().filter(cp -> cp == 0xfffd || cp == 0 || Character.getType(cp) == Character.PRIVATE_USE).count();
        return visible >= 12 && bad / (double)Math.max(1, visible) < .02 && glyphs.stream().allMatch(Glyph::valid);
    }
    static String chooseLayout(List<Glyph> glyphs, String requested) {
        if ("vertical".equals(requested)) return "vertical-rl";
        if ("horizontal".equals(requested)) return "horizontal-tb";
        long directedVertical = glyphs.stream().filter(g -> Math.abs(g.direction - 90) < 5 || Math.abs(g.direction - 270) < 5).count();
        if (directedVertical > glyphs.size() * .4) return "vertical-rl";
        int horizontalLinks=0,verticalLinks=0;
        for(int i=0;i<glyphs.size();i++)for(int j=i+1;j<glyphs.size();j++){Glyph a=glyphs.get(i),b=glyphs.get(j);if(Math.abs(a.y-b.y)<=Math.max(a.h,b.h)*.65)horizontalLinks++;if(Math.abs(a.x-b.x)<=Math.max(a.w,b.w)*.8)verticalLinks++;}
        return verticalLinks > horizontalLinks * 1.2 ? "vertical-rl" : "horizontal-tb";
    }
    static List<Block> toBlocks(List<Glyph> input, String mode, TraditionalConverter converter) {
        List<Glyph> glyphs = new ArrayList<>(input);
        Comparator<Glyph> comparator = "vertical-rl".equals(mode)
            ? Comparator.<Glyph>comparingDouble(g -> -g.x).thenComparingDouble(g -> g.y)
            : Comparator.<Glyph>comparingDouble(g -> g.y).thenComparingDouble(g -> g.x);
        glyphs.sort(comparator);
        List<List<Glyph>> groups = new ArrayList<>();
        for (Glyph glyph : glyphs) {
            List<Glyph> target = groups.stream().filter(g -> sameLine(g.get(0), glyph, mode)).findFirst().orElse(null);
            if (target == null) { target = new ArrayList<>(); groups.add(target); }
            target.add(glyph);
        }
        groups.sort((a,b) -> comparator.compare(a.get(0), b.get(0)));
        List<Block> blocks = new ArrayList<>(); int order = 0;
        for (List<Glyph> group : groups) {
            for (List<Glyph> segment : splitGaps(group, mode)) {
                String original = combine(segment, mode); if (original.isEmpty()) continue;
                double minX=1,minY=1,maxX=0,maxY=0;
                for (Glyph g : segment) { minX=Math.min(minX,g.x); minY=Math.min(minY,g.y); maxX=Math.max(maxX,g.x+g.w); maxY=Math.max(maxY,g.y+g.h); }
                blocks.add(new Block("native-" + order, "text", order++, new double[]{minX,minY,maxX-minX,maxY-minY}, mode,
                    original, converter.toSimplified(original), null, true, false, null, "native", null, null, null));
            }
        }
        return blocks;
    }
    private static boolean sameLine(Glyph a, Glyph b, String mode) {
        return "vertical-rl".equals(mode) ? Math.abs(a.x-b.x) <= Math.max(a.w,b.w)*.8 : Math.abs(a.y-b.y) <= Math.max(a.h,b.h)*.65;
    }
    private static List<List<Glyph>> splitGaps(List<Glyph> glyphs,String mode){if(glyphs.isEmpty())return List.of();List<Glyph>sorted=new ArrayList<>(glyphs);sorted.sort("vertical-rl".equals(mode)?Comparator.comparingDouble(g->g.y):Comparator.comparingDouble(g->g.x));List<List<Glyph>>out=new ArrayList<>();List<Glyph>part=new ArrayList<>();out.add(part);Glyph previous=null;for(Glyph g:sorted){if(previous!=null){double gap="vertical-rl".equals(mode)?g.y-(previous.y+previous.h):g.x-(previous.x+previous.w);double size="vertical-rl".equals(mode)?Math.max(g.h,previous.h):Math.max(g.w,previous.w);if(gap>Math.max(.025,size*3)){part=new ArrayList<>();out.add(part);}}part.add(g);previous=g;}return out;}
    private static String combine(List<Glyph> glyphs,String mode){StringBuilder out=new StringBuilder();Glyph previous=null;for(Glyph g:glyphs){if(g.text==null||g.text.isEmpty())continue;if(previous!=null&&previous.text!=null&&!previous.text.isEmpty()&&!"vertical-rl".equals(mode)){double gap=g.x-(previous.x+previous.w);int left=previous.text.codePointBefore(previous.text.length()),right=g.text.codePointAt(0);if(gap>Math.max(.003,Math.max(previous.w,g.w)*.2)&&isLatin(left)&&isLatin(right))out.append(' ');}out.append(g.text);previous=g;}return out.toString().strip();}
    private static boolean isLatin(int cp){return Character.UnicodeScript.of(cp)==Character.UnicodeScript.LATIN||Character.isDigit(cp);}
    static final class Collector extends PDFTextStripper {
        final List<Glyph> glyphs = new ArrayList<>();
        Collector() throws IOException { setSortByPosition(true); }
        @Override protected void processTextPosition(TextPosition t) {
            float pw = getCurrentPage().getCropBox().getWidth(), ph = getCurrentPage().getCropBox().getHeight();
            int rotation=Math.floorMod(getCurrentPage().getRotation(),360);if(rotation==90||rotation==270){float swap=pw;pw=ph;ph=swap;}
            double h=Math.max(.000001,t.getHeightDir()/ph);
            glyphs.add(new Glyph(t.getUnicode(), t.getXDirAdj()/pw, Math.max(0,t.getYDirAdj()/ph-h),
                Math.max(.000001,t.getWidthDirAdj()/pw), h, t.getDir()));
        }
    }
    record Glyph(String text, double x, double y, double w, double h, double direction) {
        boolean valid() { return text != null && Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(w)&&Double.isFinite(h)&&x>=0&&y>=0&&w>0&&h>0&&x+w<=1.05&&y+h<=1.05; }
    }
}
