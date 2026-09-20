package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;

import java.util.*;

@Service
public class VerticalLayoutNormalizer {
    private static final double TOP_TOLERANCE=.015;
    private static final double MAX_COLUMN_GAP=.04;
    private static final Set<String> FIGURE_TYPES=Set.of("figure","table","formula");

    public List<Block> normalize(List<Block> input,List<Block> sourceRecords){
        Map<String,Block> sources=new LinkedHashMap<>();Map<String,Integer> sourceOrder=new HashMap<>();
        for(int i=0;i<sourceRecords.size();i++){Block source=sourceRecords.get(i);sources.put(source.id(),source);sourceOrder.put(source.id(),source.order());}
        Set<String> globallyUsed=new HashSet<>();List<Block> blocks=new ArrayList<>();
        for(Block block:input){List<String>ids=orderedIds(block.sourceIds(),sources,sourceOrder);ids.removeIf(globallyUsed::contains);if(ids.isEmpty()&&!FIGURE_TYPES.contains(block.type()))continue;globallyUsed.addAll(ids);blocks.add(rebuild(block,ids,sources,block.bbox(),block.suggestion()));}

        Set<String>protectedIds=new HashSet<>();for(Block b:blocks)if(Set.of("heading","caption").contains(b.type()))protectedIds.addAll(safeIds(b));Set<String>claimed=new HashSet<>();for(Block b:blocks)if(FIGURE_TYPES.contains(b.type()))claimed.addAll(safeIds(b));
        for(int i=0;i<blocks.size();i++){Block figure=blocks.get(i);if(!FIGURE_TYPES.contains(figure.type()))continue;List<String>inside=new ArrayList<>(safeIds(figure));for(Block source:sourceRecords)if(!claimed.contains(source.id())&&!protectedIds.contains(source.id())&&isShortLabel(source)&&partitionId(source.id())==partition(figure)&&containsCenter(figure.bbox(),source.bbox())){inside.add(source.id());claimed.add(source.id());}long shortLabels=inside.stream().map(sources::get).filter(Objects::nonNull).filter(VerticalLayoutNormalizer::isShortLabel).count();if(inside.isEmpty())continue;double[]span=union(inside,sources);if(shortLabels>=2){boolean changed=true;while(changed){changed=false;for(Block source:sourceRecords){if(claimed.contains(source.id())||protectedIds.contains(source.id())||!isShortLabel(source)||partitionId(source.id())!=partition(figure))continue;if(sameVerticalBand(span,source.bbox())&&horizontalGap(span,source.bbox())<=MAX_COLUMN_GAP){inside.add(source.id());claimed.add(source.id());span=union(inside,sources);changed=true;}}}}inside=orderedIds(inside,sources,sourceOrder);if(!inside.equals(safeIds(figure)))blocks.set(i,rebuild(figure,inside,sources,padded(span,.008),append(figure.suggestion(),"图内及相邻短 OCR 标签已归入图表块")));}

        Set<String>absorbed=new HashSet<>();for(Block b:blocks)if(FIGURE_TYPES.contains(b.type()))absorbed.addAll(safeIds(b));List<Block>withoutAbsorbed=new ArrayList<>();
        for(Block block:blocks){if(FIGURE_TYPES.contains(block.type())){withoutAbsorbed.add(block);continue;}List<String>remaining=new ArrayList<>(safeIds(block));remaining.removeAll(absorbed);if(remaining.isEmpty())continue;withoutAbsorbed.add(rebuild(block,remaining,sources,union(remaining,sources),block.suggestion()));}blocks=withoutAbsorbed;

        blocks=mergeAdjacentColumns(blocks,sources,sourceOrder);
        Set<String>finalIds=new HashSet<>();for(Block b:blocks)finalIds.addAll(safeIds(b));for(Block source:sourceRecords)if(!finalIds.contains(source.id()))blocks.add(rebuild(source,List.of(source.id()),sources,source.bbox(),"结构后处理补回遗漏来源行"));
        blocks.sort(readingComparator());List<Block>result=new ArrayList<>();int order=0;for(Block b:blocks)result.add(new Block(b.id(),b.type(),order++,b.bbox(),b.writingMode(),b.original(),b.simplified(),b.confidence(),b.uncertain(),b.reviewed(),b.headingLevel(),b.source(),b.sourceIds(),b.suggestion(),b.sourceRect()));
        BlockValidator.validate(result);return List.copyOf(result);
    }

    private List<Block> mergeAdjacentColumns(List<Block>blocks,Map<String,Block>sources,Map<String,Integer>sourceOrder){List<Block>figures=blocks.stream().filter(b->FIGURE_TYPES.contains(b.type())).toList();Set<Block>consumed=Collections.newSetFromMap(new IdentityHashMap<>());List<Block>merged=new ArrayList<>();
        for(Block seed:blocks){if(consumed.contains(seed)||!mergeable(seed,figures))continue;List<Block>group=new ArrayList<>();group.add(seed);consumed.add(seed);boolean changed=true;while(changed){changed=false;for(Block candidate:blocks){if(consumed.contains(candidate)||!mergeable(candidate,figures)||partition(candidate)!=partition(seed))continue;if(group.stream().anyMatch(existing->adjacent(existing,candidate,figures))){group.add(candidate);consumed.add(candidate);changed=true;}}}if(group.size()==1){merged.add(seed);continue;}List<String>ids=new ArrayList<>();for(Block b:group)ids.addAll(safeIds(b));ids=orderedIds(ids,sources,sourceOrder);double[]bbox=unionBlocks(group);String suggestion=group.stream().map(Block::suggestion).filter(Objects::nonNull).distinct().reduce(null,VerticalLayoutNormalizer::append);Block first=group.get(0);merged.add(new Block("vertical-group-"+ids.get(0),"text",first.order(),bbox,"vertical-rl",join(ids,sources),join(ids,sources),null,group.stream().anyMatch(Block::uncertain),false,null,"qwen+minimax:vertical-normalized",List.copyOf(ids),suggestion,null));}
        for(Block b:blocks)if(!consumed.contains(b))merged.add(b);return merged;}
    private static boolean mergeable(Block b,List<Block>figures){return !isUnplaced(b)&&"text".equals(b.type())&&"vertical-rl".equals(b.writingMode())&&!safeIds(b).isEmpty()&&figures.stream().noneMatch(f->overlap(b.bbox(),f.bbox())>.05);}
    private static boolean adjacent(Block a,Block b,List<Block>figures){if(Math.abs(a.bbox()[1]-b.bbox()[1])>TOP_TOLERANCE)return false;Block right=a.bbox()[0]>=b.bbox()[0]?a:b,left=right==a?b:a;double gap=Math.max(0,right.bbox()[0]-(left.bbox()[0]+left.bbox()[2]));if(gap>MAX_COLUMN_GAP)return false;double[]span=unionBlocks(List.of(a,b));return figures.stream().noneMatch(f->overlap(span,f.bbox())>.01);}
    private static Comparator<Block> readingComparator(){return Comparator.comparingInt(VerticalLayoutNormalizer::partition).thenComparing((Block b)->-(int)Math.floor((b.bbox()[0]+b.bbox()[2])/.015)).thenComparingInt(b->FIGURE_TYPES.contains(b.type())?0:1).thenComparingDouble(b->b.bbox()[1]).thenComparingInt(Block::order);}
    private static int partition(Block b){for(String id:safeIds(b)){if(id.startsWith("R-"))return 0;if(id.startsWith("L-"))return 1;}return b.bbox()[0]+b.bbox()[2]/2>=.5?0:1;}
    private static int partitionId(String id){if(id.startsWith("R-"))return 0;if(id.startsWith("L-"))return 1;return -1;}
    private static boolean isUnplaced(Block b){return b.id().startsWith("unplaced-")||(b.suggestion()!=null&&b.suggestion().contains("自动补回"));}
    private static boolean containsCenter(double[]outer,double[]inner){double x=inner[0]+inner[2]/2,y=inner[1]+inner[3]/2;return x>=outer[0]&&x<=outer[0]+outer[2]&&y>=outer[1]&&y<=outer[1]+outer[3];}
    private static boolean isShortLabel(Block source){String text=source.original();return text!=null&&text.codePointCount(0,text.length())<=10;}
    private static boolean sameVerticalBand(double[]a,double[]b){double center=b[1]+b[3]/2;return center>=a[1]-TOP_TOLERANCE&&center<=a[1]+a[3]+TOP_TOLERANCE;}
    private static double horizontalGap(double[]a,double[]b){if(a[0]<=b[0]+b[2]&&b[0]<=a[0]+a[2])return 0;return b[0]>a[0]+a[2]?b[0]-(a[0]+a[2]):a[0]-(b[0]+b[2]);}
    private static double overlap(double[]a,double[]b){double x=Math.max(a[0],b[0]),y=Math.max(a[1],b[1]),r=Math.min(a[0]+a[2],b[0]+b[2]),bottom=Math.min(a[1]+a[3],b[1]+b[3]);double area=Math.max(0,r-x)*Math.max(0,bottom-y);return area/Math.max(1e-9,Math.min(a[2]*a[3],b[2]*b[3]));}
    private static List<String>orderedIds(List<String>ids,Map<String,Block>sources,Map<String,Integer>order){if(ids==null)return new ArrayList<>();return ids.stream().filter(sources::containsKey).distinct().sorted(Comparator.comparingInt(id->order.getOrDefault(id,Integer.MAX_VALUE))).collect(java.util.stream.Collectors.toCollection(ArrayList::new));}
    private static List<String>safeIds(Block b){return b.sourceIds()==null?List.of():b.sourceIds();}
    private static Block rebuild(Block block,List<String>ids,Map<String,Block>sources,double[]bbox,String suggestion){String original=ids.isEmpty()?Optional.ofNullable(block.original()).orElse(""):join(ids,sources);return new Block(block.id(),block.type(),block.order(),bbox,block.writingMode(),original,original,block.confidence(),block.uncertain(),block.reviewed(),block.headingLevel(),block.source(),List.copyOf(ids),suggestion,block.sourceRect());}
    private static String join(List<String>ids,Map<String,Block>sources){StringBuilder out=new StringBuilder();for(String id:ids){Block b=sources.get(id);if(b!=null&&b.original()!=null)out.append(b.original());}return out.toString();}
    private static double[]union(List<String>ids,Map<String,Block>sources){List<Block>blocks=ids.stream().map(sources::get).filter(Objects::nonNull).toList();return unionBlocks(blocks);}
    private static double[]padded(double[]v,double pad){double x=Math.max(0,v[0]-pad),y=Math.max(0,v[1]-pad),r=Math.min(1,v[0]+v[2]+pad),bottom=Math.min(1,v[1]+v[3]+pad);return new double[]{x,y,r-x,bottom-y};}
    private static double[]unionBlocks(List<Block>blocks){double x=1,y=1,r=0,bottom=0;for(Block b:blocks){double[]v=b.bbox();x=Math.min(x,v[0]);y=Math.min(y,v[1]);r=Math.max(r,v[0]+v[2]);bottom=Math.max(bottom,v[1]+v[3]);}return new double[]{x,y,r-x,bottom-y};}
    private static String append(String a,String b){return a==null||a.isBlank()?b:a+"；"+b;}
}
