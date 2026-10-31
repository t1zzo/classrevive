package dev.classrevive.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkerConcurrencyTest {
    @Test void usesHalfTheProcessorsBetweenTwoAndFour() {
        assertEquals(2, WorkerConcurrency.limit(1));
        assertEquals(2, WorkerConcurrency.limit(4));
        assertEquals(3, WorkerConcurrency.limit(6));
        assertEquals(4, WorkerConcurrency.limit(8));
        assertEquals(4, WorkerConcurrency.limit(64));
        int current = WorkerConcurrency.limit();
        assertTrue(current >= WorkerConcurrency.MIN && current <= WorkerConcurrency.MAX);
    }
}
