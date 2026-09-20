package studio.bookhtml.domain;

public record ContentIssue(String id,String kind,int start,int end,int simplifiedStart,int simplifiedEnd,
                           String reason,boolean resolved,String replacement,String inferredText) {}
