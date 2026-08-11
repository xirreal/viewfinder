package dev.xirreal.viewfinder.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MetricsCollectorTest {
   @Test
   void ringBufferAggregatesAndKeepsNewestSamples() {
      MetricsCollector.TimingRingBuffer buffer = new MetricsCollector.TimingRingBuffer(3);
      buffer.add(10);
      buffer.add(20);
      buffer.add(30);
      buffer.add(40);
      assertEquals(30, buffer.getAverage());
      assertEquals(20, buffer.getMin());
      assertEquals(40, buffer.getMax());
      assertArrayEquals(new long[] {20, 30, 40}, buffer.getSamples());
   }
}
