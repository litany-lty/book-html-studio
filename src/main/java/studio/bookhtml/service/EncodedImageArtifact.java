package studio.bookhtml.service;

import java.io.InputStream;

/**
 * B01/G10：拥有内存/临时磁盘租约的已编码图像工件（PNG/JPEG等）。
 *
 * <p>读取者通过 {@link #openStream()} 消费流式数据，关闭后释放对应字节租约。
 */
public interface EncodedImageArtifact extends AutoCloseable {

    /**
     * 打开用于读取编码字节的输入流。
     *
     * @return 新的输入流实例
     * @throws IllegalStateException 若工件已关闭
     */
    InputStream openStream();

    /**
     * 编码数据的字节长度。
     */
    long byteLength();

    /**
     * 编码数据的 SHA-256 十六进制摘要。
     */
    String sha256();

    /**
     * 返回编码数据的字节数组副本。
     *
     * @throws IllegalStateException 若工件已关闭
     */
    byte[] toByteArray();

    /**
     * 当前工件是否已关闭。
     */
    boolean isClosed();

    @Override
    void close();
}
