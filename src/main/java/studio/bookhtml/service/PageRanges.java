package studio.bookhtml.service;

import studio.bookhtml.api.ApiException;
import org.springframework.http.HttpStatus;
import java.util.*;

public final class PageRanges {
    private PageRanges() {}
    public static List<Integer> parse(String value, int totalPages) {
        if (value == null || value.isBlank()) throw bad();
        if ("all".equalsIgnoreCase(value.trim())) return java.util.stream.IntStream.rangeClosed(1, totalPages).boxed().toList();
        SortedSet<Integer> result = new TreeSet<>();
        for (String part : value.split(",")) {
            String token = part.trim();
            if (token.matches("\\d+")) add(result, Integer.parseInt(token), totalPages);
            else if (token.matches("\\d+\\s*-\\s*\\d+")) {
                String[] ends = token.split("-"); int from = Integer.parseInt(ends[0].trim()), to = Integer.parseInt(ends[1].trim());
                if (from > to) throw bad();
                for (int n = from; n <= to; n++) add(result, n, totalPages);
            } else throw bad();
        }
        if (result.isEmpty()) throw bad();
        return List.copyOf(result);
    }
    private static void add(Set<Integer> result, int n, int total) { if (n < 1 || n > total) throw bad(); result.add(n); }
    private static ApiException bad() { return new ApiException(HttpStatus.BAD_REQUEST, "页码范围无效"); }
}
