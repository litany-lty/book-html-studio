package studio.bookhtml.service;

import studio.bookhtml.domain.PageExecutionRecord;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global staged priority scheduler for page OCR and enhancement.
 *
 * Enforces progressive execution:
 * QUEUED_BASELINE -> BASELINE_RUNNING -> BASELINE_COMMITTED
 *  -> WAITING_ENHANCEMENT -> ENHANCEMENT_RUNNING -> SETTLED
 *
 * Priorities:
 * P0: Currently visible page raw/missing baseline
 * P1: User explicit retry / nearest next page baseline
 * P2: Foreground enhancement / neighbor page baseline
 * P3: Background batch / distant page / background enhancement
 *
 * Features:
 * - Current-page priority promotion without duplicate tasks.
 * - Starvation prevention: dispatches background work after at most 8 consecutive foreground tasks when no P0 is waiting.
 * - Stage release: baseline worker releases as soon as baseline commits; enhancement executes in independent pool.
 */
public class PageWorkScheduler implements AutoCloseable {

    public enum Priority {
        P0(0),
        P1(1),
        P2(2),
        P3(3);

        private final int level;
        Priority(int level) { this.level = level; }
        public int level() { return level; }
    }

    public static final class WorkItem implements Comparable<WorkItem> {
        final String key;
        final String bookId;
        final int pageNumber;
        final PageProcessingService.Request request;
        final long submissionOrder;
        final CompletableFuture<PageProcessingService.Result> future = new CompletableFuture<>();
        volatile Priority priority;
        volatile PageExecutionRecord record;
        volatile boolean running;
        volatile boolean cancelled;
        volatile Thread workerThread;

        WorkItem(String key, String bookId, int pageNumber, PageProcessingService.Request request,
                 PageExecutionRecord record, Priority priority, long submissionOrder) {
            this.key = key;
            this.bookId = bookId;
            this.pageNumber = pageNumber;
            this.request = request;
            this.record = record;
            this.priority = priority;
            this.submissionOrder = submissionOrder;
        }

        public void promote(Priority newPriority) {
            if (newPriority.level() < this.priority.level()) {
                this.priority = newPriority;
            }
        }

        @Override
        public int compareTo(WorkItem o) {
            int c = Integer.compare(this.priority.level(), o.priority.level());
            if (c != 0) return c;
            return Long.compare(this.submissionOrder, o.submissionOrder);
        }
    }

    private final PageProcessingService pageEngine;
    private final ConcurrentHashMap<String, WorkItem> activeWork = new ConcurrentHashMap<>();
    private final List<WorkItem> baselineQueue = new ArrayList<>();
    private final List<WorkItem> enhancementQueue = new ArrayList<>();
    private final Object queueLock = new Object();

    private final ExecutorService baselineExecutor;
    private final ExecutorService enhancementExecutor;
    private final AtomicLong sequence = new AtomicLong(0);
    private final AtomicInteger consecutiveForeground = new AtomicInteger(0);
    private static final int MAX_CONSECUTIVE_FOREGROUND = 8;
    private volatile boolean closed;

    public PageWorkScheduler(PageProcessingService pageEngine) {
        this(pageEngine, 4, 4);
    }

    public PageWorkScheduler(PageProcessingService pageEngine, int baselineConcurrency, int enhancementConcurrency) {
        this.pageEngine = Objects.requireNonNull(pageEngine);
        this.baselineExecutor = Executors.newFixedThreadPool(Math.max(1, baselineConcurrency), r -> {
            Thread t = new Thread(r, "page-baseline-worker");
            t.setDaemon(true);
            return t;
        });
        this.enhancementExecutor = Executors.newFixedThreadPool(Math.max(1, enhancementConcurrency), r -> {
            Thread t = new Thread(r, "page-enhancement-worker");
            t.setDaemon(true);
            return t;
        });

        for (int i = 0; i < Math.max(1, baselineConcurrency); i++) {
            baselineExecutor.submit(this::runBaselineWorker);
        }
        for (int i = 0; i < Math.max(1, enhancementConcurrency); i++) {
            enhancementExecutor.submit(this::runEnhancementWorker);
        }
    }

    public CompletableFuture<PageProcessingService.Result> schedule(PageProcessingService.Request request, Priority priority) {
        if (closed) {
            CompletableFuture<PageProcessingService.Result> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RejectedExecutionException("PageWorkScheduler is closed"));
            return failed;
        }

        String key = request.book() + ":" + request.page();
        WorkItem existing = activeWork.get(key);
        if (existing != null && !existing.future.isDone()) {
            existing.promote(priority);
            synchronized (queueLock) {
                Collections.sort(baselineQueue);
                Collections.sort(enhancementQueue);
                queueLock.notifyAll();
            }
            return existing.future;
        }

        PageExecutionRecord record = PageExecutionRecord.initial(request.attempt());
        WorkItem item = new WorkItem(key, request.book(), request.page(), request, record, priority, sequence.incrementAndGet());
        activeWork.put(key, item);

        synchronized (queueLock) {
            baselineQueue.add(item);
            Collections.sort(baselineQueue);
            queueLock.notifyAll();
        }

        return item.future;
    }

    public boolean promotePriority(String bookId, int pageNumber, Priority newPriority) {
        String key = bookId + ":" + pageNumber;
        WorkItem item = activeWork.get(key);
        if (item != null && !item.future.isDone()) {
            item.promote(newPriority);
            synchronized (queueLock) {
                Collections.sort(baselineQueue);
                Collections.sort(enhancementQueue);
                queueLock.notifyAll();
            }
            return true;
        }
        return false;
    }

    public void cancel(String bookId, int pageNumber) {
        String key = bookId + ":" + pageNumber;
        WorkItem item = activeWork.get(key);
        if (item == null || item.future.isDone()) return;

        item.cancelled = true;
        if (item.workerThread != null) {
            item.workerThread.interrupt();
        }
        synchronized (queueLock) {
            baselineQueue.remove(item);
            enhancementQueue.remove(item);
            queueLock.notifyAll();
        }

        activeWork.remove(key);
        pageEngine.finish(item.request.attempt(), "CANCELLED", "CANCELLED_CONTENT_KEPT");
        item.future.completeExceptionally(new CancelledException());
    }

    public void cancelBook(String bookId) {
        List<WorkItem> toCancel = new ArrayList<>();
        synchronized (queueLock) {
            var it = baselineQueue.iterator();
            while (it.hasNext()) {
                WorkItem item = it.next();
                if (item.bookId.equals(bookId)) {
                    item.cancelled = true;
                    it.remove();
                    toCancel.add(item);
                }
            }
            var eit = enhancementQueue.iterator();
            while (eit.hasNext()) {
                WorkItem item = eit.next();
                if (item.bookId.equals(bookId)) {
                    item.cancelled = true;
                    eit.remove();
                    toCancel.add(item);
                }
            }
            for (WorkItem item : activeWork.values()) {
                if (item.bookId.equals(bookId)) {
                    item.cancelled = true;
                    if (item.workerThread != null) {
                        item.workerThread.interrupt();
                    }
                    if (!toCancel.contains(item)) toCancel.add(item);
                }
            }
            queueLock.notifyAll();
        }

        for (WorkItem item : toCancel) {
            activeWork.remove(item.key);
            pageEngine.finish(item.request.attempt(), "CANCELLED", "CANCELLED_CONTENT_KEPT");
            item.future.completeExceptionally(new CancelledException());
        }
    }

    private WorkItem pollNext(List<WorkItem> queue) {
        synchronized (queueLock) {
            while (queue.isEmpty() && !closed) {
                try {
                    queueLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (closed || queue.isEmpty()) return null;

            // Fairness check: if consecutive foreground count >= 8 and no P0 waiting, allow background (P3) task
            boolean hasP0 = queue.stream().anyMatch(item -> item.priority == Priority.P0);
            if (consecutiveForeground.get() >= MAX_CONSECUTIVE_FOREGROUND && !hasP0) {
                for (int i = 0; i < queue.size(); i++) {
                    WorkItem candidate = queue.get(i);
                    if (candidate.priority == Priority.P3) {
                        queue.remove(i);
                        consecutiveForeground.set(0);
                        candidate.running = true;
                        return candidate;
                    }
                }
            }

            // Normal highest priority poll
            WorkItem selected = queue.remove(0);
            if (selected.priority != Priority.P3) {
                consecutiveForeground.incrementAndGet();
            } else {
                consecutiveForeground.set(0);
            }
            selected.running = true;
            return selected;
        }
    }

    private void runBaselineWorker() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            WorkItem item = pollNext(baselineQueue);
            if (item == null) break;

            item.workerThread = Thread.currentThread();
            try {
                if (item.cancelled || item.request.cancelled().getAsBoolean()) {
                    PageProcessingService.Result res = pageEngine.settle(item.request, item.record);
                    item.future.complete(res);
                    activeWork.remove(item.key);
                    continue;
                }

                PageExecutionRecord updated = pageEngine.executeBaseline(item.request, item.record);
                item.record = updated;

                if (updated.isEligibleForEnhancement() && !item.cancelled) {
                    // Stage release: baseline committed, push enhancement to enhancement queue!
                    // Baseline worker is now immediately free for the next page!
                    item.running = false;
                    synchronized (queueLock) {
                        enhancementQueue.add(item);
                        Collections.sort(enhancementQueue);
                        queueLock.notifyAll();
                    }
                } else {
                    PageProcessingService.Result res = pageEngine.settle(item.request, updated);
                    item.future.complete(res);
                    activeWork.remove(item.key);
                }
            } catch (Throwable t) {
                item.future.completeExceptionally(t);
                activeWork.remove(item.key);
            } finally {
                item.workerThread = null;
            }
        }
    }

    private void runEnhancementWorker() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            WorkItem item = pollNext(enhancementQueue);
            if (item == null) break;

            item.workerThread = Thread.currentThread();
            try {
                if (item.cancelled || item.request.cancelled().getAsBoolean()) {
                    PageProcessingService.Result res = pageEngine.settle(item.request, item.record);
                    item.future.complete(res);
                    activeWork.remove(item.key);
                    continue;
                }

                PageExecutionRecord updated = pageEngine.executeEnhancement(item.request, item.record);
                item.record = updated;
                PageProcessingService.Result res = pageEngine.settle(item.request, updated);
                item.future.complete(res);
            } catch (Throwable t) {
                item.future.completeExceptionally(t);
            } finally {
                item.workerThread = null;
                activeWork.remove(item.key);
            }
        }
    }

    public int getQueuedBaselineCount() {
        synchronized (queueLock) { return baselineQueue.size(); }
    }

    public int getQueuedEnhancementCount() {
        synchronized (queueLock) { return enhancementQueue.size(); }
    }

    public int getActiveWorkCount() {
        return activeWork.size();
    }

    @Override
    public void close() {
        closed = true;
        synchronized (queueLock) {
            queueLock.notifyAll();
        }
        baselineExecutor.shutdownNow();
        enhancementExecutor.shutdownNow();
        try {
            baselineExecutor.awaitTermination(2, TimeUnit.SECONDS);
            enhancementExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
