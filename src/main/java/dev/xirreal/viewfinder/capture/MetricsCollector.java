package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL43C;

public class MetricsCollector {

   private static final int RING_BUFFER_CAPACITY = 50;

   private final Map<String, Integer> activeQueries = new HashMap<>();
   private final Map<String, Queue<Integer>> pendingQueries = new HashMap<>();
   private final Map<String, TimingRingBuffer> passTimings = Collections.synchronizedMap(new LinkedHashMap<>());

   public void beginTiming(String passName) {
      if (activeQueries.containsKey(passName)) {
         throw new IllegalStateException("Pass '" + passName + "' is already being timed. Call endTiming first.");
      }

      collectPending(passName);
      passTimings.putIfAbsent(passName, new TimingRingBuffer(RING_BUFFER_CAPACITY));

      int query = GL43C.glGenQueries();
      GL43C.glBeginQuery(GL43C.GL_TIME_ELAPSED, query);
      activeQueries.put(passName, query);
   }

   public void endTiming(String passName) {
      Integer query = activeQueries.remove(passName);
      if (query == null) {
         return;
      }
      GL43C.glEndQuery(GL43C.GL_TIME_ELAPSED);

      pendingQueries.computeIfAbsent(passName, k -> new LinkedList<>()).add(query);
   }

   private void collectPending(String passName) {
      Queue<Integer> pendingQueue = pendingQueries.get(passName);
      if (pendingQueue == null) {
         return;
      }

      while (!pendingQueue.isEmpty()) {
         int pending = pendingQueue.peek();
         int available = GL43C.glGetQueryObjecti(pending, GL43C.GL_QUERY_RESULT_AVAILABLE);

         if (available == 0) {
            break;
         }

         pendingQueue.poll();

         long nanos = ARBTimerQuery.glGetQueryObjectui64(pending, GL43C.GL_QUERY_RESULT);
         passTimings.computeIfAbsent(passName, k -> new TimingRingBuffer(RING_BUFFER_CAPACITY)).add(nanos);

         GL43C.glDeleteQueries(pending);
      }
   }

   public Map<String, Long> getTimings() {
      Map<String, Long> result = new LinkedHashMap<>();
      for (Map.Entry<String, TimingRingBuffer> entry : passTimings.entrySet()) {
         result.put(entry.getKey(), entry.getValue().getLatest());
      }
      return result;
   }

   public JsonObject toJson() {
      JsonObject obj = new JsonObject();
      for (Map.Entry<String, TimingRingBuffer> entry : passTimings.entrySet()) {
         TimingRingBuffer buf = entry.getValue();
         JsonObject pass = new JsonObject();
         pass.addProperty("avg", buf.getAverage());
         pass.addProperty("min", buf.getMin());
         pass.addProperty("max", buf.getMax());
         pass.addProperty("latest", buf.getLatest());
         JsonArray samples = new JsonArray();
         for (long s : buf.getSamples()) {
            samples.add(s);
         }
         pass.add("samples", samples);
         obj.add(entry.getKey(), pass);
      }
      return obj;
   }

   public void reset() {
      for (Integer query : activeQueries.values()) {
         GL43C.glDeleteQueries(query);
      }
      activeQueries.clear();

      for (Queue<Integer> queue : pendingQueries.values()) {
         for (Integer query : queue) {
            GL43C.glDeleteQueries(query);
         }
      }
      pendingQueries.clear();
      passTimings.clear();
   }

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
   }
}
