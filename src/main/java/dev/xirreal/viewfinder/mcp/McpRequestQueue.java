package dev.xirreal.viewfinder.mcp;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Bounded single-worker queue that makes each MCP request non-interleavable. */
final class McpRequestQueue implements AutoCloseable {
   static final int CAPACITY = 32;

   private final int capacity;
   private final ThreadPoolExecutor executor;
   private final AtomicLong submitted = new AtomicLong();
   private final AtomicLong completed = new AtomicLong();
   private final AtomicLong rejected = new AtomicLong();
   private volatile Active active;

   McpRequestQueue() {
      this(CAPACITY);
   }

   McpRequestQueue(int capacity) {
      if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
      this.capacity = capacity;
      executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity), runnable -> {
         Thread thread = new Thread(runnable, "Viewfinder MCP Queue");
         thread.setDaemon(true);
         return thread;
      });
   }

   <T> Result<T> execute(String operation, Supplier<T> task) {
      if (operation == null || operation.isBlank()) throw new IllegalArgumentException("operation must not be blank");
      if (task == null) throw new IllegalArgumentException("task is required");

      String requestId = UUID.randomUUID().toString();
      long submittedNanos = System.nanoTime();
      int queuePosition;
      FutureTask<Result<T>> future;
      synchronized (this) {
         queuePosition = executor.getQueue().size() + executor.getActiveCount();
         int acceptedPosition = queuePosition;
         future = new FutureTask<>(() -> run(requestId, operation, acceptedPosition, submittedNanos, task));
         try {
            executor.execute(future);
            submitted.incrementAndGet();
         } catch (RejectedExecutionException e) {
            rejected.incrementAndGet();
            throw new QueueFullException(executor.isShutdown()
               ? "MCP request queue is shutting down"
               : "MCP request queue is full (capacity " + capacity + ")");
         }
      }

      try {
         return future.get();
      } catch (InterruptedException e) {
         future.cancel(false);
         if (executor.remove(future)) completed.incrementAndGet();
         Thread.currentThread().interrupt();
         throw new IllegalStateException("Interrupted while waiting for MCP request " + requestId, e);
      } catch (ExecutionException e) {
         Throwable cause = e.getCause();
         if (cause instanceof RuntimeException runtime) throw runtime;
         if (cause instanceof Error error) throw error;
         throw new IllegalStateException(cause);
      }
   }

   Snapshot snapshot() {
      Active current = active;
      int queued = executor.getQueue().size();
      String state = executor.isShutdown() ? "shutting_down" : current == null && queued == 0 ? "idle" : "busy";
      return new Snapshot(state, queued, capacity, submitted.get(), completed.get(), rejected.get(), current);
   }

   @Override
   public void close() {
      executor.shutdown();
   }

   private <T> Result<T> run(String requestId, String operation, int queuePosition, long submittedNanos, Supplier<T> task) {
      long startedNanos = System.nanoTime();
      active = new Active(requestId, operation, System.currentTimeMillis());
      try {
         T value = task.get();
         long completedNanos = System.nanoTime();
         return new Result<>(requestId, queuePosition, millis(startedNanos - submittedNanos),
            millis(completedNanos - startedNanos), value);
      } finally {
         active = null;
         completed.incrementAndGet();
      }
   }

   private static long millis(long nanos) {
      return TimeUnit.NANOSECONDS.toMillis(nanos);
   }

   record Result<T>(String requestId, int queuePosition, long queuedMillis, long executionMillis, T value) {}

   record Snapshot(String state, int queuedRequests, int capacity, long submittedRequests,
      long completedRequests, long rejectedRequests, Active active) {}

   record Active(String requestId, String operation, long startedAtMillis) {}

   static final class QueueFullException extends IllegalStateException {
      QueueFullException(String message) {
         super(message);
      }
   }
}
