package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import studio.bookhtml.config.AppProperties;

import java.nio.file.Path;

/**
 * R03 E03e：跨进程数据目录锁探针。构造成功打印 LOCKED 并退出 0；
 * 被拒绝打印原因并退出 2。仅供测试经 ProcessBuilder 调用。
 */
public final class DataDirLockProbe {
    private DataDirLockProbe() {}

    public static void main(String[] args) {
        try {
            Path data = Path.of(args[0]);
            AppProperties config = new AppProperties(data, 300, 5000, 2400, "tesseract", "", "qwen3.5-ocr",
                    "https://dashscope.aliyuncs.com/api/v1", 5, "", "MiniMax-M3", "https://api.minimax.cn/v1", 5, false);
            new BookStore(config, new ObjectMapper().findAndRegisterModules());
            System.out.println("LOCKED");
            System.exit(0);
        } catch (Exception e) {
            System.out.println("REFUSED " + String.valueOf(e.getMessage()).replaceAll("\\s+", " "));
            System.exit(2);
        }
    }
}
