package studio.bookhtml.decision;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * J01：深冻结。`List.copyOf` 保不住内部 double[] 与可变 Map；
 * 请求发出后修改源对象不得改变请求身份。
 */
public final class DeepFreeze {
    private DeepFreeze() {}

    @SuppressWarnings("unchecked")
    public static <T> T copy(T value) {
        if (value == null) return null;
        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?> || value instanceof java.time.Instant
                || value.getClass().isRecord())
            return value;
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) out.put(copy(e.getKey()), copy(e.getValue()));
            return (T) Map.copyOf(out);
        }
        if (value instanceof Set<?> set) {
            Set<Object> out = new LinkedHashSet<>();
            for (Object item : set) out.add(copy(item));
            return (T) Set.copyOf(out);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(copy(item));
            return (T) List.copyOf(out);
        }
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            Class<?> type = value.getClass().getComponentType();
            Object out = Array.newInstance(type, len);
            for (int i = 0; i < len; i++) Array.set(out, i, copy(Array.get(value, i)));
            return (T) out;
        }
        throw new IllegalArgumentException("不可冻结的类型：" + value.getClass());
    }
}
