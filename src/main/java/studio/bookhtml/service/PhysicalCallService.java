package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Shared physical lifecycle. Streaming clients validate content before accepting success;
 * the buffered adapter has an explicit validator. No retry is executed by this service. */
@Service
public class PhysicalCallService implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES=32*1024*1024;
    private final ProviderResourceRegistry resources;
    private final AttemptCallBudgetStore budgets;
    private final DelayedCallQueue delayedQueue;
    private final ObjectMapper json;
    private UsageLedger ledger;
    private CloudConsentService consentService;
    private boolean runtimeManaged;
    private volatile boolean closed;
    private BoundedHttp synchronousHttp;
    private studio.bookhtml.config.OutboundDestinationPolicy outboundPolicy=new studio.bookhtml.config.OutboundDestinationPolicy();
    /** Explicit isolated constructor used by transport-contract tests. */
    public PhysicalCallService(ProviderResourceRegistry resources,AttemptCallBudgetStore budgets,DelayedCallQueue delayed,ObjectMapper json) {
        this.resources=Objects.requireNonNull(resources);this.budgets=Objects.requireNonNull(budgets);
        this.delayedQueue=Objects.requireNonNull(delayed);this.json=Objects.requireNonNull(json);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public PhysicalCallService(ProviderResourceRegistry resources,AttemptCallBudgetStore budgets,DelayedCallQueue delayed,
                               ObjectMapper json,UsageLedger ledger,CloudConsentService consent) {
        this(resources,budgets,delayed,json);this.ledger=Objects.requireNonNull(ledger);
        this.consentService=Objects.requireNonNull(consent);runtimeManaged=true;
    }
    public void setUsageLedger(UsageLedger value) {ledger=value;}
    public void setCloudConsentService(CloudConsentService value) {consentService=value;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setOutboundPolicy(studio.bookhtml.config.OutboundDestinationPolicy value) {if(value!=null)outboundPolicy=value;}
    public studio.bookhtml.config.OutboundDestinationPolicy outboundPolicy(){return outboundPolicy;}
    public ProviderResourceRegistry resources(){return resources;}
    public AttemptCallBudgetStore budgets(){return budgets;}
    public DelayedCallQueue delayedQueue(){return delayedQueue;}
    private static final class AdmissionDenied extends OcrException {
        AdmissionDenied(String reason){super(reason);}
    }
    @FunctionalInterface interface PermitFactory {AutoCloseable acquire() throws Exception;}
    @FunctionalInterface interface ReservationFactory {PhysicalCallSession.Reservation claim() throws Exception;}
    @FunctionalInterface public interface ResponseValidator {void validate(BoundedHttp.Response response) throws Exception;}

    static PhysicalCallSession openOwned(PermitFactory admission,ReservationFactory budget,UsageLedger ledger,
            String provider,String model,long deadline,BooleanSupplier cancelled,PhysicalCallSession.RequestGuard guard) throws Exception {
        AutoCloseable permit=null;PhysicalCallSession.Reservation reservation=null;
        try {
            check(cancelled,deadline);permit=admission.acquire();
            if(permit==null)throw new AdmissionDenied("并发队列已满，保留原文");
            check(cancelled,deadline);reservation=budget.claim();
            if(reservation==null)throw new AdmissionDenied("页面调用预算不足，保留原文");
            return new PhysicalCallSession(permit,reservation,ledger,provider,model,deadline,cancelled,guard);
        } catch(Exception failure) {
            try{if(reservation!=null)reservation.close();}finally{if(permit!=null)permit.close();}
            if(failure instanceof InterruptedException){Thread.currentThread().interrupt();throw new CancelledException();}
            throw failure;
        }
    }
    PhysicalCallSession openQwen(QwenRequestGate gate,UsageLedger suppliedLedger,String model,
            QwenRequestGate.Budget budget,boolean foreground,long deadline,BooleanSupplier cancelled) throws Exception {
        if(runtimeManaged && (suppliedLedger==null || suppliedLedger!=ledger))throw new AdmissionDenied("调用账本身份不一致，未发送请求");
        UsageContext.Value context=UsageContext.current();
        PhysicalCallSession.RequestGuard guard=request->{
            if(closed)throw new AdmissionDenied("调用服务正在关闭");
            if(context==null || context.bookId()==null || context.pageNumber()==null || context.pageNumber()<1)
                throw new AdmissionDenied("调用缺少书页身份，未发送请求");
            authorize(context.bookId(),"qwen",!"HANDWRITING_TRANSCRIBE".equals(context.operation()),!foreground,null);
            if(request!=null)validateDestination(request);
        };
        guard.check(null);
        return openOwned(()->gate.acquire(foreground,Duration.ofNanos(Math.max(0,deadline-System.nanoTime())),cancelled),
                budget::claim,suppliedLedger,"qwen",model,deadline,cancelled,guard);
    }
    private static void check(BooleanSupplier cancelled,long deadline) throws OcrException {
        if(cancelled!=null && cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancelledException();
        if(deadline-System.nanoTime()<=0)throw new AdmissionDenied("调用期限已到，未发送请求");
    }
    private void authorize(String book,String provider,boolean enhance,boolean adjacent,java.util.UUID requiredConsent) throws OcrException {
        if(runtimeManaged && (ledger==null || consentService==null || book==null || book.isBlank()))throw new AdmissionDenied("调用授权或审计未装配");
        if(consentService!=null && book!=null && !book.isBlank()) {
            var active=consentService.findActiveConsent(null,book);
            if(active==null || !active.permitsBook(book) || !active.permitsProvider(provider))
                throw new AdmissionDenied("未获服务端持久云端授权");
            if(enhance && !active.permitsEnhance(adjacent))throw new AdmissionDenied("AI 辅助增强未被授权");
            if(requiredConsent!=null && !requiredConsent.equals(active.consentId()))throw new AdmissionDenied("授权身份已变化");
        }
    }
    private void validateDestination(HttpRequest request) throws OcrException {
        if(!outboundPolicy.validate(request.uri()).isAllowed())throw new AdmissionDenied("外发请求目的地址被策略阻断");
    }
    private static boolean ocr(PhysicalCallCommand.ExecutionKind kind) {
        return kind==PhysicalCallCommand.ExecutionKind.OCR || kind==PhysicalCallCommand.ExecutionKind.PADDLE_OCR
                || kind==PhysicalCallCommand.ExecutionKind.PP_OCR || kind==PhysicalCallCommand.ExecutionKind.PP_OCR_AUTH;
    }
    public CallOutcome execute(PhysicalCallCommand command,RequestFactory factory,ManagedTransport transport,BooleanSupplier cancelled) throws Exception {
        if(runtimeManaged)return new CallOutcome.NotSent("需要业务结果校验器，未发送请求");
        return execute(command,factory,transport,cancelled,this::validateJson);
    }
    /** Callers must supply their semantic validator; a full response alone is not model success. */
    public CallOutcome execute(PhysicalCallCommand command,RequestFactory factory,ManagedTransport transport,
                               BooleanSupplier cancelled,ResponseValidator validator) throws Exception {
        Objects.requireNonNull(command);Objects.requireNonNull(factory);Objects.requireNonNull(transport);Objects.requireNonNull(validator);
        long deadline=command.deadlineNanos()==0?System.nanoTime()+TimeUnit.SECONDS.toNanos(60):command.deadlineNanos();
        PhysicalCallSession.RequestGuard guard=request->{
            if(closed)throw new AdmissionDenied("调用服务正在关闭");
            UsageContext.Value current=UsageContext.current();
            if(ledger!=null && (current==null || !Objects.equals(command.bookId(),current.bookId())
                    || !Objects.equals(command.page(),current.pageNumber())))throw new AdmissionDenied("调用与账本书页不一致，未发送请求");
            authorize(command.bookId(),command.provider(),!ocr(command.kind()),!command.foreground(),command.consentId());
            if(request!=null)validateDestination(request);
        };
        PhysicalCallSession call=null;
        try {
            guard.check(null);check(cancelled,deadline);
            int max=ocr(command.kind())?AttemptCallBudgetStore.DEFAULT_OCR_BUDGET:AttemptCallBudgetStore.DEFAULT_ENHANCEMENT_BUDGET;
            call=openOwned(()->resources.acquire(command.provider(),command.foreground(),Duration.ofNanos(Math.max(0,deadline-System.nanoTime())),cancelled),
                    ()->budgets.claim(command.budgetRootId(),max),ledger,command.provider(),command.model(),deadline,cancelled,guard);
            HttpRequest request=factory.buildRequest();
            if(request==null)return new CallOutcome.NotSent("请求工厂未生成有效请求");
            var response=call.sendBuffered(request,transport,MAX_RESPONSE_BYTES);
            if(response.status()==429) {
                int seconds=DelayedCallQueue.parseRetryAfter(response.retryAfter());
                if(TimeUnit.SECONDS.toNanos(seconds)>=deadline-System.nanoTime())return new CallOutcome.Failed(429,"限流等待超过剩余期限，未提前重试",true);
                return new CallOutcome.RetryEligible(Instant.now().plusSeconds(seconds),deadline,command.logicalCallId(),seconds);
            }
            if(response.status()<200 || response.status()>=300)return new CallOutcome.Failed(response.status(),"HTTP "+response.status(),true);
            try {call.captureUsage(strict(response.body()));}catch(Exception invalidUsage){/* The validator below remains authoritative. */}
            validator.validate(response);check(cancelled,deadline);call.succeeded();
            return new CallOutcome.Succeeded(response.status(),response.body(),call.id(),Map.of());
        } catch(CancelledException cancelledCall) {throw cancelledCall;}
        catch(Exception failure) {
            // InterruptedException clears the flag. Cancellation is a control signal,
            // not an ordinary provider failure that a scheduler may choose to retry.
            if(failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();throw new CancelledException();
            }
            if(failure instanceof BoundedHttp.BoundedHttpException bounded
                    && bounded.kind()==BoundedHttp.Kind.CANCELLED)throw new CancelledException();
            if(call!=null && call.sent())return call.known()?new CallOutcome.Failed(call.status(),"响应校验或处理未完成，保留原文",true):new CallOutcome.OutcomeUnknown("传输结果未知，未自动重发");
            return new CallOutcome.NotSent(failure instanceof AdmissionDenied?failure.getMessage():"调用准备未完成，未发送请求");
        } finally {if(call!=null)call.close();}
    }
    /** Purpose determines provider, budget class and enhancement authorization, never caller-supplied text. */
    enum SynchronousPurpose {
        QWEN_OCR("qwen",false), QWEN_CROP_OCR("qwen",true), MINIMAX_ASSIST("minimax",true);
        final String provider;final boolean enhancement;
        SynchronousPurpose(String provider,boolean enhancement){this.provider=provider;this.enhancement=enhancement;}
    }
    @FunctionalInterface interface ModelParser<T> {T parse(JsonNode root) throws Exception;}
    ManagedTransport synchronousTransport() { return (request,remaining,maxBytes,stop)->http().send(request,remaining,maxBytes,stop); }
    private synchronized BoundedHttp http() throws java.io.IOException {
        if(closed)throw new java.io.IOException("shared transport closed");
        if(synchronousHttp==null)synchronousHttp=new BoundedHttp(6,Duration.ofSeconds(10));
        return synchronousHttp;
    }
    /** One network attempt. Provider-specific parsing finishes before a successful receipt. */
    <T> T invokeSynchronous(SynchronousPurpose purpose,String model,long timeoutNanos,int maxBytes,
                           RequestFactory factory,ManagedTransport transport,BooleanSupplier cancelled,ModelParser<T> parser) throws OcrException {
        Objects.requireNonNull(purpose);Objects.requireNonNull(factory);Objects.requireNonNull(transport);Objects.requireNonNull(parser);
        if(timeoutNanos<=0 || timeoutNanos>TimeUnit.SECONDS.toNanos(600) || maxBytes<1 || maxBytes>MAX_RESPONSE_BYTES)
            throw new AdmissionDenied("调用期限或响应上限无效，未发送请求");
        long started=System.nanoTime();var parent=UsageContext.current();var execution=QwenExecutionScope.current();
        if(runtimeManaged && (parent==null || execution==null))throw new AdmissionDenied("调用缺少页级执行身份，未发送请求");
        if(execution!=null && (parent==null || !execution.bookId().equals(parent.bookId())
                || !Objects.equals(execution.pageNumber(),parent.pageNumber())))throw new AdmissionDenied("执行与账本书页不一致，未发送请求");
        QwenRequestGate.Budget budget=execution==null?new QwenRequestGate.Budget(purpose.enhancement?8:5):
                purpose.enhancement?execution.budget():execution.ocrBudget();
        long deadline=budget.constrainDeadline(started+timeoutNanos);
        boolean foreground=execution==null || execution.foreground();
        try(UsageContext.Scope operation=parent==null?null:UsageContext.open(parent.bookId(),parent.pageNumber(),purpose.name(),parent.taskId())) {
            PhysicalCallSession.RequestGuard guard=request->{
                if(closed)throw new AdmissionDenied("调用服务正在关闭");
                if(parent!=null && (parent.pageNumber()==null || parent.pageNumber()<1))throw new AdmissionDenied("调用缺少有效页码");
                authorize(parent==null?null:parent.bookId(),purpose.provider,purpose.enhancement,!foreground,null);
                if(request!=null)validateDestination(request);
            };
            guard.check(null);
            try(PhysicalCallSession call=openOwned(
                    ()->resources.acquire(purpose.provider,foreground,Duration.ofNanos(Math.max(0,deadline-System.nanoTime())),cancelled),
                    budget::claim,ledger,purpose.provider,model,deadline,cancelled,guard)) {
                var response=call.sendBuffered(factory.buildRequest(),transport,maxBytes);
                if(response.status()==429) {
                    int delay=DelayedCallQueue.parseRetryAfter(response.retryAfter());
                    throw new AdmissionDenied(delay==Integer.MAX_VALUE?"模型限流等待超出当前任务期限，保留原文，未自动重发":
                            "模型请求频率受限，请至少等待 "+delay+" 秒后重新处理；未自动重发");
                }
                if(response.status()<200 || response.status()>=300)throw new AdmissionDenied("模型请求失败（HTTP "+response.status()+"）");
                JsonNode root;
                try {root=strict(response.body());}catch(Exception invalid){throw new AdmissionDenied("模型响应格式无效，保留原文");}
                call.captureUsage(root);
                try {
                    T result=Objects.requireNonNull(parser.parse(root),"model result missing");
                    check(cancelled,deadline);call.succeeded();return result;
                } catch(OcrNoTextException noText) {
                    // Valid empty OCR is a completed request, not proof that the physical page is blank.
                    check(cancelled,deadline);call.succeeded();throw noText;
                } catch(CancelledException | InterruptedException cancelledParse) {throw cancelledParse;}
                catch(Exception invalid){throw new AdmissionDenied("模型内容或引用未通过完整性校验，保留原文");}
            }
        } catch(CancelledException | OcrNoTextException expected){throw expected;}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new CancelledException();}
        catch(AdmissionDenied safe){throw safe;}
        catch(BoundedHttp.BoundedHttpException transportFailure) {
            if(transportFailure.kind()==BoundedHttp.Kind.CANCELLED)throw new CancelledException();
            throw new OcrException("模型响应读取未完成，未自动重发，保留原文");
        } catch(Exception unknown){throw new OcrException("模型调用未完成，未自动重发，保留原文");}
    }

    private JsonNode strict(byte[] bytes) throws java.io.IOException {
        try(var parser=json.getFactory().createParser(bytes)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode result=json.readTree(parser);
            if(result==null || parser.nextToken()!=null)throw new java.io.IOException("invalid response envelope");
            return result;
        }
    }
    private void validateJson(BoundedHttp.Response response) throws Exception {
        JsonNode root=strict(response.body());
        if(!root.isObject() || root.hasNonNull("error"))throw new AdmissionDenied("服务返回业务错误或非对象响应");
    }
    @Override @jakarta.annotation.PreDestroy public synchronized void close(){closed=true;delayedQueue.close();if(synchronousHttp!=null)synchronousHttp.close();}
}
