# fose

A flat combining primitive for Java. Flat combining allows threads to apply operations to a shared object on each other's behalf, when contention on a lock is detected.
***

You can simply wrap a sequential object, then submit operations against it:

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

# Features #
* `apply(fn)` and `accept(consumer)`, plus `BiFunction` / `BiConsumer` variants that take an argument.
* Configurable max operations per combining pass.
* Exceptions thrown by an operation are rethrown in the submitting thread as `CombiningException`.
* Waiting is uninterruptible. Interrupts are deferred and the status is restored on return.


# Status #
* Early. Checked with [Lincheck](https://github.com/JetBrains/lincheck) (model checking and stress tests).
* Requires Java 21.

If you run into any correctness issues or have more stress tests to contribute. Feel free to open a PR

## Maven ##

```
<dependency>
  <groupId>io.github.kusoroadeolu</groupId>
  <artifactId>fose-core</artifactId>
  <version>0.2.1</version>
</dependency>
```

# Performance #
The benchmarks are written with JMH and compare `Combiner` against a non-fair `ReentrantLock` guarding the same data. 
Also, each operation is followed by a fixed amount of busy work outside the lock simulated using `Blackhole.consumeCpu()`.

There are two workloads we benchmark against:
* **Cache line:** each operation writes to 5 counters, each on its own cache line. This is the best case for combining, since a plain lock drags all five lines across cores on every handoff.
* **Heap hold:** a 65,536-element binary min-heap (512 KB). Each operation pops the min and reinserts `min + random delta`. A larger working set, and operations that return a value.


These numbers were gotten on a machine with an Intel i5 processor with 4 physical cores and 8 logical CPUs on a Windows OS.
Note that these numbers may vary per machine.

These results are mean latency per operation in nanoseconds (lower is better).

**Cache line**

| Threads | ReentrantLock | Combiner |
|---|---|---|
| 1  | 261.4   | 267.8   |
| 2  | 313.5   | 336.0   |
| 4  | 955.6   | 566.6   |
| 6  | 1,343.9 | 644.1   |
| 8  | 1,952.8 | 838.6   |
| 16 | 3,552.8 | 3,277.4 |

**Heap hold**

| Threads | ReentrantLock | Combiner |
|---|---|---|
| 1  | 294.0   | 318.4   |
| 2  | 890.7   | 586.7   |
| 4  | 2,310.7 | 961.3   |
| 6  | 3,416.1 | 1,226.6 |
| 8  | 4,137.8 | 1,950.2 |
| 16 | 8,386.7 | 5,858.3 |

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
* Meta. [`FlatCombining.h`](https://github.com/meta/folly/blob/main/folly/synchronization/FlatCombining.h). Folly's C++ flat combining implementation.
* Meta. [`FlatCombiningBenchmark.cpp`](https://github.com/meta/folly/blob/main/folly/synchronization/test/FlatCombiningBenchmark.cpp). Folly's flat combining benchmarks.

# License #
MIT