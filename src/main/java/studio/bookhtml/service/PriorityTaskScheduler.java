package studio.bookhtml.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded local work queue. Cancellation completes running work only after its real exit. */
final class PriorityTaskScheduler implements AutoCloseable {
    private final Deque<Work<?>> queue = new ArrayDeque<>();
    private final Map<CompletableFuture<?>,Work<?>> live = new HashMap<>();
    private final List<Thread> workers = new ArrayList<>();
    private final int capacity, backgroundLimit;
    private int backgroundActive;
    private boolean closed;
    private static final class Work<T> {
        final Supplier<T> action;
        final boolean foreground;
        final CompletableFuture<T> result = new CompletableFuture<>();
        Thread runner;
        boolean stopping;
        Work(Supplier<T> action,boolean foreground) { this.action=action; this.foreground=foreground; }
        void execute() {
            try { result.complete(action.get()); }
            catch(Throwable failure) { result.completeExceptionally(failure); }
        }
    }
    PriorityTaskScheduler(int concurrency,int capacity) {
        if(concurrency<1 || capacity<1) throw new IllegalArgumentException("invalid scheduler bounds");
        this.capacity=capacity; this.backgroundLimit=Math.max(0,concurrency-1);
        for(int n=0;n<concurrency;n++) {
            Thread worker=new Thread(this::run,"qwen-priority-"+n);
            worker.setDaemon(true); workers.add(worker); worker.start();
        }
    }
    synchronized <T> CompletableFuture<T> submit(Supplier<T> action,boolean foreground) {
        if(closed || !foreground && backgroundLimit==0 || queue.size()>=(foreground?capacity:Math.max(0,capacity-1)))
            throw new RejectedExecutionException("bounded scheduler unavailable");
        Work<T> work=new Work<>(action,foreground);
        queue.addLast(work); live.put(work.result,work); notifyAll();
        return work.result;
    }
    private Work<?> eligible() {
        for(Work<?> work:queue) if(work.foreground) return work;
        return backgroundActive<backgroundLimit?queue.peekFirst():null;
    }
    private void run() {
        while(true) {
            Work<?> work;
            synchronized(this) {
                while((work=eligible())==null && !closed) {
                    try { wait(); } catch(InterruptedException ignored) { }
                }
                if(closed) return;
                queue.remove(work); work.runner=Thread.currentThread();
                if(!work.foreground) backgroundActive++;
            }
            try { work.execute(); }
            finally {
                Thread.interrupted();
                synchronized(this) {
                    live.remove(work.result);
                    if(!work.foreground) backgroundActive--;
                    work.runner=null; notifyAll();
                }
            }
        }
    }
    void cancel(CompletableFuture<?> result) {
        Work<?> queued=null;
        synchronized(this) {
            Work<?> work=live.get(result);
            if(work==null || work.stopping || result.isDone()) return;
            work.stopping=true;
            if(work.runner==null) { queue.remove(work); live.remove(result); queued=work; }
            else work.runner.interrupt();
            notifyAll();
        }
        if(queued!=null) queued.result.completeExceptionally(new CancelledException());
    }
    @Override public void close() {
        List<CompletableFuture<?>> accepted;
        synchronized(this) { closed=true; accepted=List.copyOf(live.keySet()); notifyAll(); }
        accepted.forEach(this::cancel);
    }
    synchronized boolean closed() { return closed; }
    synchronized int queued() { return queue.size(); }
}
