package studio.bookhtml.domain;

public record PageSummary(int pageNumber, String status, int blockCount, int uncertainCount,
                          double width, double height, String title, boolean reviewed) {}
