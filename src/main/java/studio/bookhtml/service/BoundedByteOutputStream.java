package studio.bookhtml.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * B01/G10：有界字节输出流。
 *
 * <p>在写入扩容前进行容量上限检查，防止单图/请求序列化造成内存失控。
 * 超过最大限制时直接抛出 {@link IOException}。
 */
public class BoundedByteOutputStream extends OutputStream {
    private final long maxBytes;
    private byte[] buf;
    private int count;

    public BoundedByteOutputStream(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes 必须大于 0");
        }
        this.maxBytes = maxBytes;
        this.buf = new byte[(int) Math.min(32 * 1024, maxBytes)];
    }

    public long maxBytes() {
        return maxBytes;
    }

    public synchronized int size() {
        return count;
    }

    public synchronized byte[] toByteArray() {
        return Arrays.copyOf(buf, count);
    }

    public synchronized void reset() {
        count = 0;
    }

    public synchronized void writeTo(OutputStream out) throws IOException {
        out.write(buf, 0, count);
    }

    private void ensureCapacity(int minCapacity) throws IOException {
        if ((long) minCapacity > maxBytes) {
            throw new IOException("输出数据大小超过安全限制 (" + minCapacity + " > " + maxBytes + " bytes)");
        }
        if (minCapacity - buf.length > 0) {
            grow(minCapacity);
        }
    }

    private void grow(int minCapacity) {
        int oldCapacity = buf.length;
        int newCapacity = oldCapacity << 1;
        if (newCapacity - minCapacity < 0) {
            newCapacity = minCapacity;
        }
        if ((long) newCapacity > maxBytes) {
            newCapacity = (int) maxBytes;
        }
        buf = Arrays.copyOf(buf, newCapacity);
    }

    @Override
    public synchronized void write(int b) throws IOException {
        ensureCapacity(count + 1);
        buf[count] = (byte) b;
        count += 1;
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        if ((off < 0) || (off > b.length) || (len < 0) ||
            ((off + len) - b.length > 0) || ((off + len) < 0)) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return;
        }
        ensureCapacity(count + len);
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }
}
