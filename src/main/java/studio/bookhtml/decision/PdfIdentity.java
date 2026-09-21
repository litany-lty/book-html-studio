package studio.bookhtml.decision;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * J01/FIX-10：PDF 内容身份。身份恒为文件内容的 SHA-256；size/mtime 只字不用作身份。
 * 每次调用完整读取计算（正确优先）；调用方在一次批量处理中复用返回值，不对每个疑点重复读盘。
 */
@Service
public class PdfIdentity {
    public String sha256(Path pdf) throws IOException {
        if (pdf == null) throw new IOException("PDF 路径为空");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(pdf)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format("%02x", b));
            return out.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("PDF 摘要计算失败", e);
        }
    }
}
