package studio.bookhtml.service;

import java.awt.image.BufferedImage;

/**
 * B01/G10：拥有内存租约的图像工件。
 *
 * <p>持有底层 raster 的字节租约；支持原子引用计数（{@link #retain()}）与子图共享所有权。
 * 当且仅当最后一个持有引用的工件被 {@link #close()} 时，释放对应租约。
 * 关闭后调用 {@link #image()} 抛出 {@link IllegalStateException}。
 */
public interface ImageArtifact extends AutoCloseable {

    /**
     * 获取受管理的图像。
     *
     * @return 图像实例
     * @throws IllegalStateException 若工件已关闭
     */
    BufferedImage image();

    /**
     * 当前工件保留的堆内存字节数。
     */
    long reservedBytes();

    /**
     * 增加引用计数并返回共享相同底层光栅租约的新工件包装。
     */
    ImageArtifact retain();

    /**
     * 从当前图像派生子图，共享底层光栅租约引用（不重复扣减内存）。
     */
    ImageArtifact subImage(int x, int y, int w, int h);

    /**
     * 当前工件是否已显式关闭。
     */
    boolean isClosed();

    @Override
    void close();
}
