package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.opengl.GL33C;

public class MetricsCollector {

   private static final int RING_BUFFER_CAPACITY = 50;

   private final Deque<ActiveTiming> activeTimings = new ArrayDeque<>();
   private final Queue<PendingTiming> pendingTimings = new ArrayDeque<>();
   private final Map<String, TimingRingBuffer> passTimings = Collections.synchronizedMap(new LinkedHashMap<>());
   private int framesRemaining;
   private int framesCaptured;
   private boolean captureEnding;
   private boolean captureIncludesSamples = true;
   private CompletableFuture<JsonObject> capture;

   public void beginTiming(String passName) {
      if (framesRemaining <= 0) return;
      if (!activeTimings.isEmpty()) {
         activeTimings.peek().hasChildren = true;
      }

      int startQuery = GL33C.glGenQueries();
      GL33C.glQueryCounter(startQuery, GL33C.GL_TIMESTAMP);
      activeTimings.push(new ActiveTiming(passName, startQuery));
   }

   public void endTiming() {
      ActiveTiming timing = activeTimings.poll();
      if (timing == null) {
         return;
      }

      if (timing.hasChildren) {
         GL33C.glDeleteQueries(timing.startQuery);
         return;
      }

      int endQuery = GL33C.glGenQueries();
      GL33C.glQueryCounter(endQuery, GL33C.GL_TIMESTAMP);
      pendingTimings.add(new PendingTiming(timing.name, timing.startQuery, endQuery));
   }

   public void endFrame() {
      collectPending();
      if (framesRemaining > 0) {
         framesCaptured++;
         if (--framesRemaining == 0) captureEnding = true;
      }
      if (!captureEnding || !activeTimings.isEmpty() || !pendingTimings.isEmpty()) return;
      CompletableFuture<JsonObject> done = capture;
      capture = null;
      captureEnding = false;
      if (done != null) done.complete(toJson(captureIncludesSamples));
   }

   public CompletableFuture<JsonObject> captureFrames(int frames) {
      return captureFrames(frames, true);
   }

   public CompletableFuture<JsonObject> captureFrames(int frames, boolean includeSamples) {
      if (frames < 1 || frames > 600) throw new IllegalArgumentException("frames must be between 1 and 600");
      reset();
      framesRemaining = frames;
      framesCaptured = 0;
      captureEnding = false;
      captureIncludesSamples = includeSamples;
      capture = new CompletableFuture<>();
      return capture;
   }

   public JsonObject finishCapture() {
      collectPending();
      JsonObject result = new JsonObject();
      result.addProperty("framesCaptured", framesCaptured);
      result.add("passes", toJson(captureIncludesSamples));
      discardQueries();
      framesRemaining = 0;
      captureEnding = false;
      CompletableFuture<JsonObject> pending = capture;
      capture = null;
      if (pending != null && !pending.isDone()) pending.complete(result.getAsJsonObject("passes"));
      return result;
   }

   public boolean isCapturing() {
      return framesRemaining > 0 || captureEnding;
   }

   private void collectPending() {
      while (!pendingTimings.isEmpty()) {
         PendingTiming pending = pendingTimings.peek();
         int available = GL33C.glGetQueryObjecti(pending.endQuery, GL33C.GL_QUERY_RESULT_AVAILABLE);

         if (available == 0) {
            break;
         }

         pendingTimings.poll();
         long start = GL33C.glGetQueryObjectui64(pending.startQuery, GL33C.GL_QUERY_RESULT);
         long end = GL33C.glGetQueryObjectui64(pending.endQuery, GL33C.GL_QUERY_RESULT);
         long nanos = Math.max(0L, end - start);
         synchronized (passTimings) {
            passTimings.computeIfAbsent(pending.name, k -> new TimingRingBuffer(RING_BUFFER_CAPACITY)).add(nanos);
         }

         GL33C.glDeleteQueries(pending.startQuery);
         GL33C.glDeleteQueries(pending.endQuery);
      }
   }

   public Map<String, Long> getTimings() {
      Map<String, Long> result = new LinkedHashMap<>();
      synchronized (passTimings) {
         for (Map.Entry<String, TimingRingBuffer> entry : passTimings.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getLatest());
         }
      }
      return result;
   }

   public JsonObject toJson() {
      return toJson(true);
   }

   public JsonObject toJson(boolean includeSamples) {
      JsonObject obj = new JsonObject();
      synchronized (passTimings) {
         for (Map.Entry<String, TimingRingBuffer> entry : passTimings.entrySet()) {
            obj.add(entry.getKey(), entry.getValue().toJson(includeSamples));
         }
      }
      return obj;
   }

   public void reset() {
      discardQueries();
      synchronized (passTimings) {
         passTimings.clear();
      }
      framesRemaining = 0;
      framesCaptured = 0;
      captureEnding = false;
      captureIncludesSamples = true;
      CompletableFuture<JsonObject> pending = capture;
      capture = null;
      if (pending != null) pending.completeExceptionally(new IllegalStateException("Profile capture was reset"));
   }

   private void discardQueries() {
      for (ActiveTiming timing : activeTimings) {
         GL33C.glDeleteQueries(timing.startQuery);
      }
      activeTimings.clear();

      for (PendingTiming timing : pendingTimings) {
         GL33C.glDeleteQueries(timing.startQuery);
         GL33C.glDeleteQueries(timing.endQuery);
      }
      pendingTimings.clear();
   }

   private static final class ActiveTiming {

      private final String name;
      private final int startQuery;
      private boolean hasChildren;

      private ActiveTiming(String name, int startQuery) {
         this.name = name;
         this.startQuery = startQuery;
      }
   }

   private record PendingTiming(String name, int startQuery, int endQuery) {}

   public static class TimingRingBuffer {

      private final long[] buffer;
      private int writeIndex;
      private int count;

      public TimingRingBuffer(int capacity) {
         this.buffer = new long[capacity];
      }

      public void add(long value) {
         buffer[writeIndex] = value;
         writeIndex = (writeIndex + 1) % buffer.length;
         if (count < buffer.length) {
            count++;
         }
      }

      public long getLatest() {
         if (count == 0) return 0;
         int idx = (writeIndex - 1 + buffer.length) % buffer.length;
         return buffer[idx];
      }

      public long getAverage() {
         if (count == 0) return 0;
         long sum = 0;
         for (int i = 0; i < count; i++) {
            sum += buffer[i];
         }
         return sum / count;
      }

      public long getMin() {
         if (count == 0) return 0;
         long min = Long.MAX_VALUE;
         for (int i = 0; i < count; i++) {
            if (buffer[i] < min) min = buffer[i];
         }
         return min;
      }

      public long getMax() {
         if (count == 0) return 0;
         long max = Long.MIN_VALUE;
         for (int i = 0; i < count; i++) {
            if (buffer[i] > max) max = buffer[i];
         }
         return max;
      }

      public long[] getSamples() {
         long[] result = new long[count];
         if (count < buffer.length) {
            System.arraycopy(buffer, 0, result, 0, count);
         } else {
            int start = writeIndex;
            for (int i = 0; i < count; i++) {
               result[i] = buffer[(start + i) % buffer.length];
            }
         }
         return result;
      }

      JsonObject toJson(boolean includeSamples) {
         JsonObject result = new JsonObject();
         result.addProperty("sampleCount", count);
         result.addProperty("avg", getAverage());
         result.addProperty("min", getMin());
         result.addProperty("max", getMax());
         result.addProperty("latest", getLatest());
         if (includeSamples) {
            JsonArray samples = new JsonArray();
            for (long sample : getSamples()) samples.add(sample);
            result.add("samples", samples);
         }
         return result;
      }
   }
}
