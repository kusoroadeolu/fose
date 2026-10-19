# fose

A flat combining primitive for Java. Flat combining allows threads to apply operations to a shared object on each other's behalf, when contention on a lock is detected.
## Usage

You can simply wrap a sequential object, then submit operations against it:

```java
class ThreadSafePriorityQueue<E> {
    private final Combiner<PriorityQueue<E>> combiner =
            Combiner.builder(new PriorityQueue<E>()).build();

    public void push(E e) {
        combiner.accept(queue -> queue.offer(e));
    }

    public E popMinimum() {
        return combiner.apply(queue -> queue.poll());
    }
}
```

`apply` takes a `Function` and returns its result. `accept` is the same thing for operations with no result.

Requires Java 17.

## Configuration

```java
Combiner<Heap> combiner = Combiner.builder(heap)
        .maxCombiningOperations(100) // max ops a lock holder serves per pass (default 100)
        .exposeMetrics()             // off by default
        .build();
```

With metrics enabled, `combiner.metrics()` returns a `CombinerMetrics`:

| Field | Meaning |
|---|---|
| `totalCombinedOperations` | operations applied on behalf of other threads |
| `totalPasses` | times a lock holder walked the publication queue |
| `combiningPasses` | passes that applied at least one other thread's op |
| `combinedOperationsPerPass` | `totalCombinedOperations / totalPasses` |

`resetMetrics()` zeroes them. If metrics aren't enabled, `metrics()` returns all zeroes.

## Benchmarks

JMH, compared against a `StampedLock` write lock guarding the same data. Numbers are average `ns/op` per thread, so lower is better.

Machine: 4 core Intel i5, 8 CPUs, 16 GB RAM on a Windows OS. Results from a single machine, so treat them as indicative, not universal. Rows past 8 threads are oversubscribed.

### Cache line benchmark

Each op updates 5 counters spread over separate cache lines, then does ~1000 units of work outside the lock.

| Threads | `add` baseline | `add` combined | Diff | `fetchAdd` diff |
|---|---|---|---|---|
| 1 | 1631.7 | 1623.4 | -0.5% | +0.3% |
| 2 | 1730.7 | 1753.0 | +1.3% | -3.4% |
| 4 | 2114.1 | 1879.3 | -11.1% | -18.4% |
| 8 | 3480.2 | 2168.8 | -37.7% | -54.2% |
| 16 | 8215.0 | 4340.7 | -47.2% | -54.3% |

### Heap benchmark

Each op pops the min of a 65,536-element binary heap and reinserts `min + random delta`, so the size stays constant.

| Threads | work=0 diff | work=500 diff |
|---|---|---|
| 1 | -7.5% | -8.0% |
| 2 | -37.4% | -10.2% |
| 4 | -28.2% | -60.5% |
| 8 | -12.5% | -70.2% |
| 16 | -9.9% | -69.5% |

Single-threaded, there's no meaningful difference, which is the intent. The gap opens up as contention does.

## Building and running

```
mvn clean package
java -jar fose-jmh/target/benchmark.jar
```

This runs both benchmarks across a thread sweep (1 up to 2x your core count) and writes `jmh-threads-<n>.json` for each.

Tests live in `fose-core` and use [Lincheck](https://github.com/JetBrains/lincheck):

```
mvn test
```

## Modules

| Module | Contents |
|---|---|
| `fose-core` | `Combiner` and its internals |
| `fose-jmh` | JMH benchmarks |