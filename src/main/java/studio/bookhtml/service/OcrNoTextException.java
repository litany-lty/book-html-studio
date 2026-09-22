package studio.bookhtml.service;

/** Structurally valid provider response with no recognized content.
 * Never use for transport, authentication, truncation or malformed geometry errors.
 */
public final class OcrNoTextException extends OcrException {
    public OcrNoTextException(String message) { super(message); }
}
