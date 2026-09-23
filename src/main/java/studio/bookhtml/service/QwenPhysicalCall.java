package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Shared send boundary for Qwen structure, text review, whole-page assist and TOC recovery.
 * Queued/rejected work is not a physical call. Unknown remote outcomes are never refunded.
 */
final class QwenPhysicalCall implements AutoCloseable {
    private static final BoundedHttp BODIES = new BoundedHttp(3, Duration.ofSeconds(10));
    private final QwenRequestGate.Permit permit;
    private final QwenRequestGate.Budget.Reservation reservation;
    private final UsageLedger ledger;
    private final BooleanSupplier cancelled;
    private final long deadline;
    private final String id;
    private InputStream responseBody;
    private boolean sent, known, successful, closed;

    @FunctionalInterface interface Sender { HttpResponse<InputStream> send(HttpRequest request) throws Exception; }

    static QwenPhysicalCall open(QwenRequestGate gate, UsageLedger ledger, String model,
                                 QwenRequestGate.Budget requestedBudget, boolean foreground,
                                 int timeoutSeconds, BooleanSupplier cancelled, boolean managed) throws Exception {
        if (managed && (gate == null || ledger == null)) throw new OcrException("调用审计或并发控制未装配，未发送请求");
        QwenRequestGate.Budget budget = QwenExecutionScope.budgetOr(requestedBudget);
        if (budget == null) budget = gate == null ? new QwenRequestGate.Budget(8) : gate.newBudget();
        long deadline = budget.deadline(timeoutSeconds);
        QwenRequestGate.Permit permit = null;
        QwenRequestGate.Budget.Reservation reservation = null;
        try {
            checkCancelled(cancelled);
            if (System.nanoTime() >= deadline) throw new OcrException("Qwen 共享执行期限已到，未发送请求");
            if (gate != null) {
                permit = gate.acquire(foreground, Duration.ofNanos(Math.max(0,deadline-System.nanoTime())));
                if (permit == null) throw new OcrException("Qwen 并发队列已满，保留原文");
            }
            checkCancelled(cancelled);
            if (System.nanoTime() >= deadline) throw new OcrException("Qwen 排队已超过本次期限，未发送请求");
            reservation = budget.claim();
            if (reservation == null) throw new OcrException("页面增强调用预算不足，保留原文");
            String id = ledger == null ? null : ledger.prepare("qwen",model);
            return new QwenPhysicalCall(permit,reservation,ledger,cancelled,deadline,id);
        } catch (Exception error) {
            if (reservation != null) reservation.close();
            if (permit != null) permit.close();
            if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); throw new CancelledException(); }
            throw error;
        }
    }
    private QwenPhysicalCall(QwenRequestGate.Permit permit, QwenRequestGate.Budget.Reservation reservation,
                             UsageLedger ledger, BooleanSupplier cancelled, long deadline, String id) {
        this.permit=permit; this.reservation=reservation; this.ledger=ledger;
        this.cancelled=cancelled; this.deadline=deadline; this.id=id;
    }
    HttpResponse<InputStream> send(HttpRequest request, Sender transport) throws Exception {
        checkCancelled(cancelled);
        if (sent || closed) throw new IllegalStateException("physical call is single use");
        if (deadline-System.nanoTime() <= 0) throw new OcrException("Qwen 请求期限已到，未发送请求");
        if (ledger != null) ledger.sending(id); // Durable possible-send interval precedes transport.
        // Disk admission itself can block; do not let it extend the network deadline.
        checkCancelled(cancelled);
        long remaining = deadline-System.nanoTime();
        if (remaining <= 0) throw new OcrException("Qwen 审计后期限已到，未发送请求");
        HttpRequest timed = HttpRequest.newBuilder(request, (name,value) -> true)
                .timeout(Duration.ofNanos(remaining)).build();
        reservation.markSent(); sent=true;
        HttpResponse<InputStream> response = transport.send(timed);
        if (response == null) throw new OcrException("Qwen 未返回响应，远端结果未知");
        responseBody=response.body();
        known=response.statusCode()<200 || response.statusCode()>=300;
        checkCancelled(cancelled);
        return response;
    }
    byte[] read(HttpResponse<InputStream> response, int maxBytes) throws Exception {
        try {
            byte[] bytes=BODIES.consumeResponse(response,deadline,maxBytes,cancelled).body();
            known=true;
            return bytes;
        } catch (BoundedHttp.BoundedHttpException failure) {
            if(failure.kind()==BoundedHttp.Kind.CANCELLED) throw new CancelledException();
            if(failure.kind()==BoundedHttp.Kind.TOO_LARGE) throw new OcrException("Qwen 返回内容过大");
            throw new OcrException(failure.kind()==BoundedHttp.Kind.TIMEOUT
                    ? "Qwen 响应体读取超时，远端结果未知" : "Qwen 响应体读取失败，远端结果未知");
        }
    }
    void captureUsage(JsonNode root) {
        if (ledger != null) try { ledger.captureUsage(id,root); }
        catch (Exception failure) { warn(); }
    }
    void succeeded() {
        if(!sent || !known || closed) throw new IllegalStateException("call not completed");
        successful=true;
    }
    boolean sent() { return sent; }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancelledException();
    }
    @Override public void close() {
        if (closed) return;
        closed=true;
        try {
            if (responseBody != null) try { responseBody.close(); } catch (Exception ignored) { }
            if (ledger != null) try {
                if (!sent) ledger.notSent(id);
                else if (successful) ledger.succeeded(id);
                else if (known) ledger.failed(id);
                else ledger.unknown(id);
            } catch (Exception failure) { warn(); }
        } finally {
            reservation.close();
            if (permit != null) permit.close();
        }
    }
    private static void warn() {
        System.getLogger(QwenPhysicalCall.class.getName()).log(System.Logger.Level.WARNING,
                "QWEN_LEDGER_SETTLEMENT_UNCONFIRMED: preserving audit uncertainty; no automatic resend");
    }
}
