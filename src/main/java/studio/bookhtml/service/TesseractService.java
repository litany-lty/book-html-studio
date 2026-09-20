package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Block;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@Service
public class TesseractService {
    private final String command;
    private final TraditionalConverter converter;
    public TesseractService(AppProperties properties, TraditionalConverter converter) { this.command = properties.tesseractCommand(); this.converter = converter; }

    public List<Block> recognize(BufferedImage full, String layout, boolean splitSpreads, BooleanSupplier cancelled) throws OcrException {
        if ("auto".equals(layout)) {
            List<Block> vertical = recognizeLayout(full, "vertical", splitSpreads, cancelled);
            List<Block> horizontal = recognizeLayout(full, "horizontal", splitSpreads, cancelled);
            return score(vertical) >= score(horizontal) ? vertical : horizontal;
        }
        return recognizeLayout(full, layout, splitSpreads, cancelled);
    }
    private List<Block> recognizeLayout(BufferedImage full, String layout, boolean splitSpreads, BooleanSupplier cancelled) throws OcrException {
        List<Slice> slices = splitSpreads && full.getWidth() > full.getHeight() * 1.15 ? split(full, layout) : List.of(new Slice(full, 0, 0, full.getWidth(), full.getHeight()));
        List<Block> result = new ArrayList<>(); int sequence = 0;
        for (Slice slice : slices) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancelledException();
            for (WordLine line : run(slice.image, layout, cancelled)) {
                double x = (slice.x + line.x) / full.getWidth(), y = (slice.y + line.y) / full.getHeight();
                double w = line.w / full.getWidth(), h = line.h / full.getHeight();
                String text = line.text.strip(); if (text.isEmpty()) continue;
                result.add(new Block("ocr-" + sequence, "text", sequence++, new double[]{x,y,w,h}, mode(layout), text,
                    converter.toSimplified(text), line.confidence, true, false, null, "tesseract", null, null, null));
            }
        }
        return result;
    }
    private static double score(List<Block> blocks) { return blocks.stream().mapToDouble(b -> (b.original()==null?0:b.original().codePointCount(0,b.original().length())) * (b.confidence()==null?.25:b.confidence())).sum(); }
    private List<WordLine> run(BufferedImage image, String layout, BooleanSupplier cancelled) throws OcrException {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("book-html-ocr-").toRealPath();
            Path input = dir.resolve("page.png"), output = dir.resolve("result.tsv"), error = dir.resolve("error.txt");
            ImageIO.write(image, "png", input.toFile());
            String language = "vertical".equals(layout) ? "chi_tra_vert" : "chi_tra+chi_sim+eng";
            String psm = "vertical".equals(layout) ? "5" : "6";
            Process process = new ProcessBuilder(command, input.toString(), "stdout", "-l", language, "--psm", psm, "tsv")
                .redirectOutput(output.toFile()).redirectError(error.toFile()).start();
            long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) { process.destroy(); if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly(); throw new CancelledException(); }
                if (System.nanoTime() > deadline) { process.destroyForcibly(); throw new OcrException("本地 OCR 超时"); }
            }
            if (process.exitValue() != 0) throw new OcrException("本地 OCR 执行失败（退出码 " + process.exitValue() + "）");
            return parseTsv(Files.readAllLines(output, StandardCharsets.UTF_8));
        } catch (CancelledException e) { throw e; }
        catch (IOException e) { throw new OcrException("无法执行本地 OCR，请检查 Tesseract 配置", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancelledException(); }
        finally { if (dir != null) deleteTree(dir); }
    }
    static List<WordLine> parseTsv(List<String> lines) {
        Map<String,List<Word>> groups = new LinkedHashMap<>();
        for (int i=1;i<lines.size();i++) {
            String[] c=lines.get(i).split("\\t",12); if(c.length<12 || c[11].isBlank()) continue;
            try { double conf=Double.parseDouble(c[10]); if(conf<0) continue; String key=c[1]+":"+c[2]+":"+c[3]+":"+c[4];
                groups.computeIfAbsent(key,k->new ArrayList<>()).add(new Word(Integer.parseInt(c[6]),Integer.parseInt(c[7]),Integer.parseInt(c[8]),Integer.parseInt(c[9]),conf,c[11]));
            } catch(NumberFormatException ignored) { }
        }
        List<WordLine> result=new ArrayList<>();
        for(List<Word> words:groups.values()) { int x=Integer.MAX_VALUE,y=Integer.MAX_VALUE,r=0,b=0; double conf=0; StringJoiner text=new StringJoiner(" ");
            for(Word w:words){x=Math.min(x,w.x);y=Math.min(y,w.y);r=Math.max(r,w.x+w.w);b=Math.max(b,w.y+w.h);conf+=w.conf;text.add(w.text);}
            result.add(new WordLine(x,y,r-x,b-y,Math.max(0,Math.min(1,conf/words.size()/100d)),text.toString())); }
        return result;
    }
    static List<Slice> split(BufferedImage image,String layout){int mid=image.getWidth()/2; Slice left=new Slice(image.getSubimage(0,0,mid,image.getHeight()),0,0,mid,image.getHeight()); Slice right=new Slice(image.getSubimage(mid,0,image.getWidth()-mid,image.getHeight()),mid,0,image.getWidth()-mid,image.getHeight()); return "horizontal".equals(layout)?List.of(left,right):List.of(right,left);}
    private static String mode(String layout){return "vertical".equals(layout)?"vertical-rl":"horizontal-tb";}
    private static void deleteTree(Path root){try(var s=Files.walk(root)){s.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}catch(IOException ignored){}}
    record Slice(BufferedImage image,int x,int y,int w,int h){}
    record Word(int x,int y,int w,int h,double conf,String text){}
    record WordLine(int x,int y,int w,int h,double confidence,String text){}
}
