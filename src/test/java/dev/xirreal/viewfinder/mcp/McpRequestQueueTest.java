package dev.xirreal.viewfinder.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class McpRequestQueueTest {
   @Test
   void executesConcurrentRequestsInSubmissionOrderWithoutInterleaving() throws Exception {
      try (McpRequestQueue queue = new McpRequestQueue()) {
         List<String> events = Collections.synchronizedList(new ArrayList<>());
         CountDownLatch firstStarted = new CountDownLatch(1);
         CountDownLatch releaseFirst = new CountDownLatch(1);

         CompletableFuture<Void> first = CompletableFuture.runAsync(() -> queue.execute("first", () -> {
            events.add("first:start");
            firstStarted.countDown();
            await(releaseFirst);
            events.add("first:end");
            return null;
         }));
         assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

         CompletableFuture<Void> second = CompletableFuture.runAsync(() -> queue.execute("second", () -> {
            events.add("second");
            return null;
         }));
         awaitQueued(queue, 1);
         CompletableFuture<Void> third = CompletableFuture.runAsync(() -> queue.execute("third", () -> {
            events.add("third");
            return null;
         }));
         awaitQueued(queue, 2);

         releaseFirst.countDown();
         CompletableFuture.allOf(first, second, third).get(5, TimeUnit.SECONDS);
         assertEquals(List.of("first:start", "first:end", "second", "third"), events);
         assertEquals(3, queue.snapshot().completedRequests());
      }
   }

   @Test
   void rejectsRequestsBeyondTheBoundedQueue() throws Exception {
      try (McpRequestQueue queue = new McpRequestQueue(1)) {
         CountDownLatch firstStarted = new CountDownLatch(1);
         CountDownLatch releaseFirst = new CountDownLatch(1);
         CompletableFuture<Void> first = CompletableFuture.runAsync(() -> queue.execute("first", () -> {
            firstStarted.countDown();
            await(releaseFirst);
            return null;
         }));
         assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
         CompletableFuture<Void> second = CompletableFuture.runAsync(() -> queue.execute("second", () -> null));
         awaitQueued(queue, 1);

         assertThrows(IllegalStateException.class, () -> queue.execute("overflow", () -> null));
         assertEquals(1, queue.snapshot().capacity());
         releaseFirst.countDown();
         CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
         assertEquals(1, queue.snapshot().rejectedRequests());
      }
   }

   private static void awaitQueued(McpRequestQueue queue, int expected) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (queue.snapshot().queuedRequests() != expected && System.nanoTime() < deadline) Thread.onSpinWait();
      assertEquals(expected, queue.snapshot().queuedRequests());
   }

   private static void await(CountDownLatch latch) {
      try {
         latch.await();
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new IllegalStateException(e);
      }
   }
}
