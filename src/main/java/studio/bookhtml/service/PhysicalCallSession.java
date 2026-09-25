package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One physical send owner shared by buffered and streaming adapters. A successful HTTP
 * header is not a validated model result; only the caller's validator can accept success. */
final class PhysicalCallSession implements AutoCloseable {
    interface Reservation extends AutoCloseable { void markSent(); @Override void close(); }
    @FunctionalInterface interface RequestGuard { void check(HttpRequest request) throws Exception; }
    @FunctionalInterface interface Sender { HttpResponse<InputStream> send(HttpRequest request) throws Exception; }
    private static final BoundedHttp BODIES=new BoundedHttp(3,Duration.ofSeconds(10));
    private final Thread owner=Thread.currentThread();
    private final AutoCloseable permit;
    private final Reservation reservation;
    private final UsageLedger ledger;
    private final UsageContext.Value context;
    private final BooleanSupplier cancelled;
    private final RequestGuard guard;
    private final long deadline;
    private final String id;
    private HttpResponse<InputStream> response;
    private InputStream stream;
    private boolean attempted,known,successful,closed,bodyRead;
    private int status;

    PhysicalCallSession(AutoCloseable permit,Reservation reservation,UsageLedger ledger,String provider,String model,
                        long deadline,BooleanSupplier cancelled,RequestGuard guard) throws Exception {
        this.permit=permit;this.reservation=Objects.requireNonNull(reservation);this.ledger=ledger;
        this.deadline=deadline;this.cancelled=cancelled==null?()->false:cancelled;this.guard=Objects.requireNonNull(guard);
        context=UsageContext.current();check();
        id=ledger==null?null:ledger.prepare(provider,model);
    }
    String id() {return id;}
    boolean sent() {return attempted;}
    boolean known() {return known;}
    int status() {return status;}
    private void requireOwner() {if(Thread.currentThread()!=owner)throw new IllegalStateException("physical call ownership changed");}
    private void check() {
        requireOwner();if(closed)throw new IllegalStateException("physical call already closed");
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancelledException();
        if(ledger!=null && !Objects.equals(context,UsageContext.current()))throw new IllegalStateException("physical accounting context changed");
    }
    private HttpRequest prepareSend(HttpRequest request) throws Exception {
        check();if(attempted)throw new IllegalStateException("physical call is single use");
        Objects.requireNonNull(request);guard.check(request);
        if(deadline-System.nanoTime()<=0)throw new OcrException("调用期限已到，未发送请求");
        if(ledger!=null)ledger.sending(id);
        // Revalidate policy/cancellation after durable I/O and after potentially slow request construction.
        guard.check(request);check();
        long remaining=deadline-System.nanoTime();
        if(remaining<=0)throw new OcrException("审计后期限已到，未发送请求");
        return HttpRequest.newBuilder(request,(name,value)->true).timeout(Duration.ofNanos(remaining)).build();
    }
    HttpResponse<InputStream> send(HttpRequest request,Sender transport) throws Exception {
        HttpRequest timed=prepareSend(request);
        reservation.markSent();attempted=true;
        response=transport.send(timed);
        if(response==null)throw new OcrException("未返回响应，远端结果未知");
        stream=response.body();status=response.statusCode();known=status<200 || status>=300;
        check();return response;
    }
    byte[] read(HttpResponse<InputStream> value,int maxBytes) throws Exception {
        check();
        if(!attempted || value!=response || bodyRead || maxBytes<1)throw new IllegalArgumentException("response ownership or size mismatch");
        try {
            byte[] bytes=BODIES.consumeResponse(value,deadline,maxBytes,cancelled).body();
            bodyRead=true;known=true;return bytes;
        } catch(BoundedHttp.BoundedHttpException failure) {
            if(failure.kind()==BoundedHttp.Kind.CANCELLED)throw new CancelledException();
            if(failure.kind()==BoundedHttp.Kind.TOO_LARGE)throw new OcrException("返回内容过大");
            throw new OcrException(failure.kind()==BoundedHttp.Kind.TIMEOUT?"响应体读取超时，远端结果未知":"响应体读取失败，远端结果未知");
        }
    }
    BoundedHttp.Response sendBuffered(HttpRequest request,ManagedTransport transport,int maxBytes) throws Exception {
        if(maxBytes<1)throw new IllegalArgumentException("invalid response limit");
        HttpRequest timed=prepareSend(request);
        long remaining=deadline-System.nanoTime();
        if(remaining<=0)throw new OcrException("传输前期限已到，未发送请求");
        reservation.markSent();attempted=true;
        var value=transport.send(timed,remaining,maxBytes,cancelled);
        if(value==null)throw new OcrException("未返回响应，远端结果未知");
        status=value.status();known=true;bodyRead=true;check();
        if(value.body().length>maxBytes)throw new OcrException("返回内容过大");
        if(deadline-System.nanoTime()<=0)throw new OcrException("响应已超过执行期限，未接受结果");
        return value;
    }
    void captureUsage(JsonNode root) {
        requireOwner();if(!attempted || closed)throw new IllegalStateException("no active send to account");
        if(ledger!=null)try {ledger.captureUsage(id,root);}catch(Exception failure){warn();}
    }
    void succeeded() {
        check();
        if(!attempted || !known || !bodyRead || status<200 || status>=300)throw new IllegalStateException("response not completely successful");
        if(deadline-System.nanoTime()<=0)throw new IllegalStateException("result arrived after execution deadline");
        successful=true;
    }
    @Override public void close() {
        requireOwner();if(closed)return;closed=true;
        // Cancellation must survive finalization, but a preexisting interrupt must
        // not close the WAL channel before this owner's NOT_SENT/unknown receipt is saved.
        boolean restoreInterrupt=Thread.interrupted();
        try {
            if(stream!=null)try {stream.close();}catch(Exception ignored){}
            if(ledger!=null)try(UsageContext.Scope original=UsageContext.open(context.bookId(),context.pageNumber(),context.operation(),context.taskId())) {
                if(!attempted)ledger.notSent(id);
                else if(successful)ledger.succeeded(id);
                else if(known)ledger.failed(id);
                else ledger.unknown(id);
            }catch(Exception unavailable){warn();}
        } finally {
            try {
                try {reservation.close();}finally{if(permit!=null)try{permit.close();}catch(Exception unavailable){warn();}}
            } finally {if(restoreInterrupt)Thread.currentThread().interrupt();}
        }
    }
    private static void warn() {
        System.getLogger(PhysicalCallSession.class.getName()).log(System.Logger.Level.WARNING,
                "PHYSICAL_CALL_SETTLEMENT_UNCONFIRMED: no automatic resend or inferred refund");
    }
}
