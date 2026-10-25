package io.github.kusoroadeolu.fose;

import org.jetbrains.lincheck.Lincheck;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.LockSupport;

class CombinerLincheckTest {
    static class Adder {
        int i;

        void add() {
            ++i;
        }

        int sum() {
            return i;
        }
    }

    static class ThrowingAdder {
        Adder adder = new Adder();
        public void throwingAdd() {
            throw new RuntimeException();
        }

        public void add() {
            adder.add();
        }

        public int sum() {
            return adder.sum();
        }
    }

    private static final int INVOCATIONS = 32;

    @Test  //testing that the memory effects of the operations in t1, t2, t3 are visible to the asserting thread
    public void assertHappensBeforeVisibility() {
        Lincheck.runConcurrentTest(INVOCATIONS, () -> {
            Combiner<Adder> c = new Combiner<>(new Adder());
            Runnable task = () -> c.accept(Adder::add);
            Thread t = new Thread(task);
            Thread t1 = new Thread(task);
            Thread t2 = new Thread(task);
            t.start();
            t1.start();
            t2.start();

            try {
                t.join();
                t1.join();
                t2.join();
            }catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            Assertions.assertEquals(3, c.apply(Adder::sum));
        });
    }

    @Test
    public void assertNoLostWrites() {
        Lincheck.runConcurrentTest(INVOCATIONS, () -> {
            Combiner<Queue<Integer>> c = new Combiner<>(new LinkedList<>());
            c.accept(l -> l.addAll(List.of(1, 2, 3)));

            Runnable task = () -> {
                Integer value = c.apply(Queue::poll);

                Assertions.assertNotNull(value);
            };

            Thread t = new Thread(task);
            Thread t1 = new Thread(task);
            Thread t2 = new Thread(task);
            t.start();
            t1.start();
            t2.start();

            try {
                t.join();
                t1.join();
                t2.join();
            }catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    public void assertExceptionsDoNotHaltProgress() {
        Lincheck.runConcurrentTest(INVOCATIONS, () -> {
            Combiner<ThrowingAdder> c = new Combiner<>(new ThrowingAdder());

            Runnable throwingTask = () -> Assertions.assertThrows(CombiningException.class, () -> c.accept(ThrowingAdder::throwingAdd));

            Runnable task = () -> Assertions.assertDoesNotThrow(() -> c.accept(ThrowingAdder::add));

            Thread t = new Thread(throwingTask);
            Thread t1 = new Thread(throwingTask);
            Thread t2 = new Thread(task);
            t.start();
            t1.start();
            t2.start();

            try {
                t.join();
                t1.join();
                t2.join();
            }catch (InterruptedException e) {
                throw new AssertionError(e);
            }

            Assertions.assertEquals(1, c.apply(ThrowingAdder::sum));
        });
    }

    @Test
    public void assertEachCallerGetsItsOwnResult() {
        Lincheck.runConcurrentTest(INVOCATIONS, () -> {
            Combiner<Adder> c = new Combiner<>(new Adder());
            int[] results = new int[3];
            Thread[] threads = new Thread[3];

            for (int i = 0; i < 3; i++) {
                int idx = i;
                threads[i] = new Thread(() -> results[idx] = c.apply(a -> a.i++));
                threads[i].start();
            }

            try {
                for (Thread t : threads) t.join();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }

            Arrays.sort(results);
            Assertions.assertArrayEquals(new int[]{0, 1, 2}, results);
        });
    }

    @Test
    public void assertLocalWritesStayOrdered() {
        Lincheck.runConcurrentTest(INVOCATIONS, () -> {
            Combiner<Queue<Integer>> c = new Combiner<>(new LinkedList<>());

            //Should see a then b then c, regardless of inter leavings with other threads, otherwise program order is violated
            Runnable task = () -> submitThenVerifyProgramOrder(c, List.of(1, 2, 3));

            Runnable task1 = () -> submitThenVerifyProgramOrder(c, List.of(4, 5, 6));

            Runnable task2 = () -> submitThenVerifyProgramOrder(c, List.of(7, 8, 9));


            Thread t = new Thread(task);
            Thread t1 = new Thread(task1);
            Thread t2 = new Thread(task2);
            t.start();
            t1.start();
            t2.start();

            try {
                t.join();
                t1.join();
                t2.join();
            }catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
    }

    void submitThenVerifyProgramOrder(Combiner<Queue<Integer>> c, List<Integer> nums) {
        for (int n : nums) {
            c.accept(l -> l.add(n));
        }


        List<Integer> seen =  c.apply(l -> l.stream().filter(nums::contains).toList());
        for (int i = 0; i < nums.size(); ++i) {
            Assertions.assertEquals(nums.get(i), seen.get(i));
        }
    }
}