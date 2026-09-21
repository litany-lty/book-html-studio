package studio.bookhtml.decision;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * J01：规范序列化——固定键序、明确 UTF-8、无空白装饰。
 * 不得对普通 HashMap 的不稳定字符串直接求 hash；所有身份 hash 经此输出计算。
 */
public final class CanonicalJson {
    private CanonicalJson() {}

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void append(StringBuilder out, Object value) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof String s) { quote(out, s); return; }
        if (value instanceof Boolean || value instanceof Number) { out.append(value.toString()); return; }
        if (value instanceof Enum<?> e) { quote(out, e.name()); return; }
        if (value instanceof Instant t) { out.append(t.toEpochMilli()); return; }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) sorted.put(String.valueOf(e.getKey()), e.getValue());
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(out, e.getKey());
                out.append(':');
                append(out, e.getValue());
            }
            out.append('}');
            return;
        }
        if (value instanceof Collection<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) out.append(',');
                first = false;
                append(out, item);
            }
            out.append(']');
            return;
        }
        if (value.getClass().isArray()) {
            out.append('[');
            int len = Array.getLength(value);
            for (int i = 0; i < len; i++) {
                if (i > 0) out.append(',');
                append(out, Array.get(value, i));
            }
            out.append(']');
            return;
        }
        if (value.getClass().isRecord()) {
            try {
                RecordComponent[] components = value.getClass().getRecordComponents();
                List<RecordComponent> sorted = new ArrayList<>(List.of(components));
                sorted.sort(Comparator.comparing(RecordComponent::getName));
                out.append('{');
                boolean first = true;
                for (RecordComponent c : sorted) {
                    if (!first) out.append(',');
                    first = false;
                    quote(out, c.getName());
                    out.append(':');
                    append(out, c.getAccessor().invoke(value));
                }
                out.append('}');
                return;
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("记录序列化失败：" + value.getClass(), e);
            }
        }
        throw new IllegalArgumentException("不支持规范序列化的类型：" + value.getClass());
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
