package studio.bookhtml.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Bounded image evidence only. A visual block label is not proof of a text-free page.
 * Grid removal happens on a disposable analysis mask, NEVER on the source/recognition image.
 * Possible text is a conservative recovery trigger, not a handwriting classifier or confidence.
 */
public final class ScanTextEvidence {
    private static final int MAX_SIDE = 1200;
    public record Evidence(boolean nearBlank, boolean possibleText, int glyphComponents, int occupiedCells) {}
    private ScanTextEvidence() {}

    public static Evidence inspect(BufferedImage source) {
        if (source == null) return new Evidence(false, false, 0, 0);
        double scale = Math.min(1, MAX_SIDE / (double)Math.max(source.getWidth(), source.getHeight()));
        int w = Math.max(1, (int)Math.round(source.getWidth()*scale));
        int h = Math.max(1, (int)Math.round(source.getHeight()*scale));
        BufferedImage sample = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sample.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, w, h, null);
        } finally { g.dispose(); }
        byte[] dark = new byte[w*h], faint = new byte[w*h];
        int blackCount = 0, darkCount = 0, faintCount = 0;
        try {
            for (int y=0; y<h; y++) for (int x=0; x<w; x++) {
                int rgb = sample.getRGB(x,y);
                int gray = (((rgb>>16)&255)*30 + ((rgb>>8)&255)*59 + (rgb&255)*11)/100;
                int i = y*w+x;
                if (gray<185) { dark[i]=1; blackCount++; }
                if (gray<210) darkCount++;
                if (gray<235) { faint[i]=1; faintCount++; }
            }
        } finally { sample.flush(); }
        int[] queue = new int[w*h]; // at most 5.8 MB; not dependent on source dimensions
        // Even one coherent faint mark vetoes blankness. Whole-page ink ratios alone
        // discard small marginal writing and pale text. A few isolated dust pixels do not.
        boolean lowInk = blackCount < w*h*.001 && darkCount < w*h*.002 && faintCount < w*h*.01;
        if (lowInk && !hasMark(faint, w, h, queue)) return new Evidence(true, false, 0, 0);
        byte[] mask = dark.clone();
        stripLongRules(dark, mask, w, h);
        boolean[] cells = new boolean[36];
        int glyphs=0;
        for (int i=0; i<mask.length; i++) {
            if (mask[i]==0) continue;
            int head=0, tail=1, minX=i%w, maxX=minX, minY=i/w, maxY=minY;
            queue[0]=i; mask[i]=0;
            while(head<tail) {
                int at=queue[head++], x=at%w, y=at/w;
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)
                    for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++) {
                        int next=yy*w+xx;
                        if(mask[next]!=0){mask[next]=0;queue[tail++]=next;}
                    }
            }
            int cw=maxX-minX+1, ch=maxY-minY+1;
            if(cw>=3 && ch>=3 && cw<=Math.max(12,w*.09) && ch<=Math.max(12,h*.14)
                    && tail>=6 && cw/(double)ch>.1 && cw/(double)ch<8 && tail/(double)(cw*ch)>.06) {
                glyphs++;
                cells[Math.min(5,(minY+maxY)*3/h)*6 + Math.min(5,(minX+maxX)*3/w)]=true;
            }
        }
        int occupied=0;for(boolean cell:cells)if(cell)occupied++;
        return new Evidence(false, glyphs>=18 && occupied>=4, glyphs, occupied);
    }
    private static boolean hasMark(byte[] mask,int w,int h,int[] queue) {
        for(int i=0;i<mask.length;i++) {
            if(mask[i]==0)continue;
            int head=0,tail=1;queue[0]=i;mask[i]=0;
            while(head<tail) {
                int at=queue[head++],x=at%w,y=at/w;
                if(head>=6)return true;
                for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)
                    for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++) {
                        int next=yy*w+xx;if(mask[next]!=0){mask[next]=0;queue[tail++]=next;}
                    }
            }
        }
        return false;
    }
    private static void stripLongRules(byte[] source,byte[] target,int w,int h) {
        for(int y=0;y<h;y++)for(int x=0;x<w;) {
            if(source[y*w+x]==0){x++;continue;}
            int start=x;while(x<w && source[y*w+x]!=0)x++;
            if(x-start>=Math.max(24,w/12))for(int xx=start;xx<x;xx++)target[y*w+xx]=0;
        }
        for(int x=0;x<w;x++)for(int y=0;y<h;) {
            if(source[y*w+x]==0){y++;continue;}
            int start=y;while(y<h && source[y*w+x]!=0)y++;
            if(y-start>=Math.max(24,h/12))for(int yy=start;yy<y;yy++)target[yy*w+x]=0;
        }
    }
}
