package studio.bookhtml.service;

import studio.bookhtml.domain.PageExecutionRecord;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded two-stage scheduler. Physical worker exit, not logical future cancellation, releases ownership. */
public class PageWorkScheduler implements AutoCloseable {
    public enum Priority { P0(0),P1(1),P2(2),P3(3);private final int level;Priority(int level){this.level=level;}public int level(){return level;} }
    private static final int MAX_ACTIVE=64,FAIRNESS=8;
    private final PageProcessingService engine;
    private final Object lock=new Object();
    private final Map<String,Work> active=new HashMap<>();
    private final List<Work> baseline=new ArrayList<>(),enhancement=new ArrayList<>();
    private final ExecutorService baselineWorkers,enhancementWorkers;
    private final AtomicLong sequence=new AtomicLong();
    private final int[] foregroundRuns=new int[2];
    private volatile boolean closed;
    private static final class Work implements Comparable<Work> {
        final String key;final long order;final PageProcessingService.Request request;
        final CompletableFuture<PageProcessingService.Result> future=new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt){return false;} // Only scheduler cancellation knows physical ownership.
        };
        volatile boolean cancelled;boolean interruptionSent,running,settling;
        volatile Thread worker;
        Priority priority;PageExecutionRecord record;QwenExecutionScope.Value scope;
        Work(PageProcessingService.Request source,Priority priority,long order) {
            this.key=source.book()+":"+source.page();this.priority=priority;this.order=order;
            this.request=new PageProcessingService.Request(source.attempt(),source.provider(),source.layout(),source.split(),source.force(),source.assist(),
                    ()->cancelled||source.cancelled().getAsBoolean(),source.owned());
            this.record=PageExecutionRecord.initial(source.attempt());
        }
        public int compareTo(Work other){int c=Integer.compare(priority.level(),other.priority.level());return c==0?Long.compare(order,other.order):c;}
    }
    public PageWorkScheduler(PageProcessingService engine){this(engine,3,2);}
    public PageWorkScheduler(PageProcessingService engine,int baseConcurrency,int enhanceConcurrency) {
        this.engine=Objects.requireNonNull(engine);
        int first=Math.max(1,Math.min(8,baseConcurrency)),second=Math.max(1,Math.min(8,enhanceConcurrency));
        baselineWorkers=pool(first,"page-baseline-worker");enhancementWorkers=pool(second,"page-enhancement-worker");
        for(int i=0;i<first;i++)baselineWorkers.execute(()->run(false));
        for(int i=0;i<second;i++)enhancementWorkers.execute(()->run(true));
    }
    private static ExecutorService pool(int n,String name){return Executors.newFixedThreadPool(n,r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;});}
    public CompletableFuture<PageProcessingService.Result> schedule(PageProcessingService.Request request,Priority priority) {
        Objects.requireNonNull(request);Objects.requireNonNull(priority);
        synchronized(lock) {
            if(closed)return CompletableFuture.failedFuture(new RejectedExecutionException("page scheduler closed"));
            String key=request.book()+":"+request.page();Work old=active.get(key);
            if(old!=null) {
                if(!old.request.id().equals(request.id())||old.request.attempt().generation()!=request.attempt().generation())
                    return CompletableFuture.failedFuture(new RejectedExecutionException("another page attempt still owns this slot"));
                if(priority.level()<old.priority.level())old.priority=priority;
                Collections.sort(baseline);Collections.sort(enhancement);lock.notifyAll();return old.future;
            }
            int limit=priority==Priority.P3?MAX_ACTIVE-1:MAX_ACTIVE;
            if(active.size()>=limit)return CompletableFuture.failedFuture(new RejectedExecutionException("page queue capacity reached"));
            Work work=new Work(request,priority,sequence.incrementAndGet());
            active.put(key,work);baseline.add(work);Collections.sort(baseline);lock.notifyAll();return work.future;
        }
    }
    public boolean promotePriority(String book,int page,Priority priority) {
        synchronized(lock){Work work=active.get(book+":"+page);if(work==null||work.cancelled)return false;
            if(priority.level()<work.priority.level())work.priority=priority;Collections.sort(baseline);Collections.sort(enhancement);lock.notifyAll();return true;}
    }
    private Work take(boolean second) {
        synchronized(lock) {
            List<Work> queue=second?enhancement:baseline;int lane=second?1:0;
            while(queue.isEmpty()&&!closed)try{lock.wait();}catch(InterruptedException e){if(closed)return null;}
            if(closed)return null;
            int selected=0;
            if(foregroundRuns[lane]>=FAIRNESS && queue.stream().noneMatch(w->w.priority==Priority.P0))
                for(int i=0;i<queue.size();i++)if(queue.get(i).priority==Priority.P3){selected=i;break;}
            Work work=queue.remove(selected);foregroundRuns[lane]=work.priority==Priority.P3?0:foregroundRuns[lane]+1;
            work.running=true;work.worker=Thread.currentThread();return work;
        }
    }
    private void run(boolean second) {
        while(!closed) {
            Work work=take(second);if(work==null)return;
            try {
                if(work.scope==null)work.scope=engine.executionScope(work.request);
                try(QwenExecutionScope scope=work.scope==null?null:QwenExecutionScope.attach(work.scope)) {
                    if(work.cancelled||work.request.cancelled().getAsBoolean())work.record=work.record.withSettled("CANCELLED","CANCELLED_CONTENT_KEPT",null);
                    else work.record=second?engine.executeEnhancement(work.request,work.record):engine.executeBaseline(work.request,work.record);
                    if(work.record==null)throw new IllegalStateException("page engine returned no execution record");
                }
                if(!second && work.record.isEligibleForEnhancement()) {
                    synchronized(lock) {
                        if(!closed&&!work.cancelled) {
                            // Transfer ownership before enqueue. The baseline finalizer never clears a successor thread.
                            work.running=false;work.worker=null;enhancement.add(work);Collections.sort(enhancement);lock.notifyAll();continue;
                        }
                    }
                }
            } catch(Throwable failure) {
                work.record=work.record.withSettled(work.cancelled?"CANCELLED":work.record.publishedRevision()==null?"FAILED":"PARTIAL",
                        "PAGE_STAGE_UNFINISHED",work.record.publishedRevision());
            }
            settle(work);
            Thread.interrupted(); // A cancelled task must not poison the next owned page on this worker.
        }
    }
    private void settle(Work work) {
        synchronized(lock){if(work.settling)return;work.settling=true;}
        PageProcessingService.Result result=null;Throwable failure=null;
        try {
            if(work.cancelled)work.record=work.record.withSettled("CANCELLED","CANCELLED_CONTENT_KEPT",work.record.publishedRevision());
            result=engine.settle(work.request,work.record);
            if(result==null)result=new PageProcessingService.Result(work.record.lifecycle(),work.record.messageCode(),work.record.publishedRevision());
        } catch(Throwable error){failure=error;}
        finally {synchronized(lock){work.worker=null;work.running=false;active.remove(work.key,work);lock.notifyAll();}}
        if(work.cancelled)work.future.completeExceptionally(new CancelledException());
        else if(failure!=null)work.future.completeExceptionally(failure);else work.future.complete(result);
    }
    public void cancel(String book,int page) {
        Work immediate=null;
        synchronized(lock) {
            Work work=active.get(book+":"+page);if(work==null||work.settling)return;
            work.cancelled=true;baseline.remove(work);enhancement.remove(work);
            if(work.running) {
                if(!work.interruptionSent&&work.worker!=null){work.interruptionSent=true;work.worker.interrupt();}
            } else immediate=work;
            lock.notifyAll();
        }
        if(immediate!=null)settle(immediate);
    }
    public void cancelBook(String book){List<Work> selected;synchronized(lock){selected=active.values().stream().filter(w->w.request.book().equals(book)).toList();}
        for(Work work:selected)cancel(work.request.book(),work.request.page());}
    public int getQueuedBaselineCount(){synchronized(lock){return baseline.size();}}
    public int getQueuedEnhancementCount(){synchronized(lock){return enhancement.size();}}
    public int getActiveWorkCount(){synchronized(lock){return active.size();}}
    @Override public void close() {
        List<Work> all;synchronized(lock){closed=true;all=List.copyOf(active.values());lock.notifyAll();}
        for(Work work:all)cancel(work.request.book(),work.request.page());
        baselineWorkers.shutdown();enhancementWorkers.shutdown();
        boolean interrupted=Thread.interrupted();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        try {
            for(var pool:List.of(baselineWorkers,enhancementWorkers))while(!pool.isTerminated()) {
                long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalStateException("page workers still draining");
                try{pool.awaitTermination(remaining,TimeUnit.NANOSECONDS);}catch(InterruptedException e){interrupted=true;}
            }
        } finally {if(interrupted)Thread.currentThread().interrupt();}
    }
}
