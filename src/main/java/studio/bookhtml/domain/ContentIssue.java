package studio.bookhtml.domain;

import studio.bookhtml.decision.DecisionModels;

/**
 * 内容疑点。resolution 为可选确认来源元数据（J09/J01-6.6），随 Page 同次持久化；
 * 历史 JSON 缺该字段时读为 null（LEGACY_UNKNOWN），绝不补造确认事实；
 * 所有复制构造必须显式保留它，不能依赖旧重载静默清空。
 */
public record ContentIssue(String id,String kind,int start,int end,int simplifiedStart,int simplifiedEnd,
                           String reason,boolean resolved,String replacement,String inferredText,
                           DecisionModels.ReviewResolution resolution) {
    /** 旧 11 参数兼容构造：resolution 缺省 null，保证历史 JSON 与既有调用点兼容。 */
    public ContentIssue(String id,String kind,int start,int end,int simplifiedStart,int simplifiedEnd,
                        String reason,boolean resolved,String replacement,String inferredText){
        this(id,kind,start,end,simplifiedStart,simplifiedEnd,reason,resolved,replacement,inferredText,null);
    }
}
