package io.github.kusoroadeolu.fose;

import org.jetbrains.lincheck.Lincheck;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CombinerTest {
    static class Adder {
        int i;

        int add() {
            return i++;
        }

        int sum() {
            return i;
        }
    }

    @Test
    public void sanityCombinerTest() {
        Lincheck.runConcurrentTest(() -> {
            Combiner<Adder> c = new Combiner<>(new Adder());
            Runnable inc = () -> c.accept(Adder::add);
            Thread t = new Thread(inc);
            Thread t1 = new Thread(inc);
            Thread t2 = new Thread(inc);
            t.start();
            t1.start();
            t2.start();

            try {
                t.join();
                t1.join();
                t2.join();
            }catch (InterruptedException _) {}
            Assertions.assertEquals(3, c.accept(Adder::sum));
        });

    }
}