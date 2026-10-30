# fose

A flat combining primitive for Java. Flat combining allows threads to apply operations to a shared object on each other's behalf, when contention on a lock is detected.
***

Wrap a sequential object, then submit operations against it:

```java
class ThreadSafePriorityQueue<E> {
    private final Combiner<PriorityQueue<E>> combiner =
            new Combiner<>(new PriorityQueue<>());

    public void push(E e) {
        combiner.accept(queue -> queue.offer(e));
    }

    public E popMin() {
        return combiner.apply(queue -> queue.poll());
    }
}
```

# Design #
* A lock guards the shared object. A thread that wins the lock runs its own operation, then serves the queue.
* A thread that loses enqueues a node holding its operation (a CAS on the queue tail), then waits for a result.
* The lock holder (the combiner) walks the queue, applies each operation, and publishes the result on the node.
* A combining pass is capped at `maxOps` (default 25) so one thread can't get stuck serving others forever.
* Waiters near the head of the queue spin briefly, then park. Waiters further back, and virtual threads, park immediately.
* Parked threads are signalled with a Dekker-style handshake (similar to AQS), so wakeups aren't lost.
* `state`, `tail`, `head` and the sequence counter are spread across padded superclasses to avoid false sharing.

See the comments in `Combiner.java` for a more detailed explanation.

# Features #
* `apply(fn)` and `accept(consumer)`, plus `BiFunction` / `BiConsumer` variants that take an argument.
* Configurable max operations per combining pass.
* Exceptions thrown by an operation are rethrown in the submitting thread as `CombiningException`.
* Waiting is uninterruptible. Interrupts are deferred and the status is restored on return.
* Works with virtual threads.

# Status #
* Early. Checked with [Lincheck](https://github.com/JetBrains/lincheck) (model checking and stress tests).
* Requires Java 21.

## Maven ##

```
<dependency>
  <groupId>io.github.kusoroadeolu</groupId>
  <artifactId>fose-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

# Performance #
Benchmarks are written with JMH and compare `Combiner` against a non-fair `ReentrantLock` guarding the same data. Each operation is followed by a fixed amount of busy work outside the lock simulated using `Blackhole.consumeCpu()`.
Results show sampled per-operation latency in nanoseconds, measured with 1 thread up to 2x your machine’s logical CPU count.

There are two workloads we benchmark against:
* **Cache line:** each operation writes to 5 counters, each on its own cache line. This is the best case for combining, since a plain lock drags all five lines across cores on every handoff.
* **Heap hold:** a 65,536-element binary min-heap (512 KB). Each operation pops the min and reinserts `min + random delta`. A larger working set, and operations that return a value.

- Machine: Intel i5 (4 cores / 8 logical CPUs), Windows, JDK 21.

Mean latency per operation in nanoseconds (lower is better). Speedup is `ReentrantLock / Combiner`.

**Cache line**

| Threads | ReentrantLock | Combiner | Speedup |
|---|---|---|---|
| 1  | 260.8   | 273.5   | 0.95x |
| 2  | 303.6   | 394.0   | 0.77x |
| 4  | 998.5   | 655.7   | 1.52x |
| 6  | 1,454.8 | 797.1   | 1.83x |
| 8  | 1,893.8 | 967.9   | 1.96x |
| 16 | 3,514.5 | 3,399.9 | 1.03x |

**Heap hold**

| Threads | ReentrantLock | Combiner | Speedup |
|---|---|---|---|
| 1  | 294.6   | 308.3   | 0.96x |
| 2  | 897.5   | 588.6   | 1.52x |
| 4  | 2,113.7 | 968.3   | 2.18x |
| 6  | 3,015.9 | 1,436.9 | 2.10x |
| 8  | 4,044.8 | 2,507.7 | 1.61x |
| 16 | 7,921.7 | 4,569.3 | 1.73x |

## Building and running ##

```
mvn clean package
java -jar fose-jmh/target/benchmark.jar
```

This runs the benchmarks across the thread sweep and writes `jmh-threads-<n>.json` for each.

Tests live in `fose-core`:

```
mvn test
```

## Modules ##

| Module | Contents |
|---|---|
| `fose-core` | `Combiner` and its internals |
| `fose-jmh` | JMH benchmarks |

# References #
* Hendler, Incze, Shavit, Tzafrir. [Flat Combining and the Synchronization-Parallelism Tradeoff](https://doi.org/10.1145/1810479.1810540). SPAA 2010.
* Fatourou, Kallimanis. Revisiting the combining synchronization technique. PPoPP 2012.

# License #
MIT