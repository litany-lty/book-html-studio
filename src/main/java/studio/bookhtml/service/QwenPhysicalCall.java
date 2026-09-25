package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Compatibility adapter: all modern Qwen paths delegate physical ownership to the shared gateway. */
final class QwenPhysicalCall implements AutoCloseable {
    @FunctionalInterface interface Sender {HttpResponse<InputStream> send(HttpRequest request) throws Exception;}
    private final PhysicalCallSession session;
    private QwenPhysicalCall(PhysicalCallSession session){this.session=session;}
    static QwenPhysicalCall open(QwenRequestGate gate,UsageLedger ledger,String model,QwenRequestGate.Budget requestedBudget,
            boolean foreground,int timeoutSeconds,BooleanSupplier cancelled,boolean managed) throws Exception {
        if(managed && (gate==null || ledger==null))throw new OcrException("调用审计或并发控制未装配，未发送请求");
        var inherited=QwenExecutionScope.budgetOr(requestedBudget);
        final QwenRequestGate.Budget budget=inherited!=null?inherited:gate==null?new QwenRequestGate.Budget(8):gate.newBudget();
        long deadline=budget.deadline(timeoutSeconds);
        PhysicalCallSession session;
        if(gate!=null && gate.physicalService()!=null) {
            session=gate.physicalService().openQwen(gate,ledger,model,budget,foreground,deadline,cancelled);
        } else {
            // Explicit, non-Spring test fixtures still exercise the same send/close state machine.
            session=PhysicalCallService.openOwned(()->gate==null?()->{}:
                    gate.acquire(foreground,Duration.ofNanos(Math.max(0,deadline-System.nanoTime())),cancelled),
                    budget::claim,ledger,"qwen",model,deadline,cancelled,request->{});
        }
        return new QwenPhysicalCall(session);
    }
    HttpResponse<InputStream> send(HttpRequest request,Sender transport) throws Exception {return session.send(request,transport::send);}
    byte[] read(HttpResponse<InputStream> response,int maxBytes) throws Exception {return session.read(response,maxBytes);}
    void captureUsage(JsonNode root){session.captureUsage(root);}
    void succeeded(){session.succeeded();}
    boolean sent(){return session.sent();}
    @Override public void close(){session.close();}
}
