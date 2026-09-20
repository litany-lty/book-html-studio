package studio.bookhtml.service;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import org.springframework.stereotype.Service;

@Service
public class TraditionalConverter {
    public String toSimplified(String text) { return text == null ? "" : ZhConverterUtil.toSimple(text); }
}
