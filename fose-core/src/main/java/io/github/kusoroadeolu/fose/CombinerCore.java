package io.github.kusoroadeolu.fose;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;

class CombinerPad0 {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15;
}

class CombinerLockField extends CombinerPad0 {
    final Lock lock;

    public CombinerLockField() {
        this.lock = new StampedLock().asWriteLock();
    }
}

class CombinerPad1 extends CombinerLockField {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;
}

class CombinerWaiterField<E> extends CombinerPad1 {
    volatile Node<E> tail;
}

class CombinerPad2<E> extends CombinerWaiterField<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;
}

class CombinerFields<E> extends CombinerPad2<E> {
    Node<E> head;
    final E item;
    //ideally we'd use an int, but to avoid hotspot reordering this with the tail fail due to alignment gaps, we use a long
    //will switch to byte padding later to avoid this
    final long maxOps; //max operations per combining session
    final boolean exposeMetrics;

    //metrics
    long _combined;
    long _passes;
    long _total_ops;

    public CombinerFields(E item, int maxOps, boolean exposeMetrics) {
        lock.lock();
        try {
            this.item = item; //purely for visibility
        }finally {
            lock.unlock();
        }
        this.maxOps = maxOps;
        this.exposeMetrics = exposeMetrics;
    }
}

class CombinerPad3<E> extends CombinerFields<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;


    public CombinerPad3(E item, int maxOps, boolean exposeMetrics) {
        super(item, maxOps, exposeMetrics);
    }
}


/*
* The idea of flat combining was introduced in the 2010 SPAA paper, Flat
* Combining and the Synchronization-Parallelism Tradeoff (Hendler, Incze, Shavit, Tzafrir)
*
* Flat combining can be used as an alternative to fine-grained locking for making a
* sequential structure thread-safe while minimizing the synchronization overhead and cache coherence
* faced when waiting to acquire a lock.
*
* The basic idea of flat combining is when a thread finds a lock contended, it can
* request for its operation to be applied by the lock holder. This is done by the thread enqueueing
* a node object, containing its operation (expressed through a function/consumer lambda)
* and periodically checking whether its operation has been applied or the lock is held.
*
* The publication queue uses an implementation of the Dmitry Vyukov MPSC unbounded queue
* Enqueues onto the queue involve an atomic xchg operation of the queue's tail to a thread's node
* and then linking the previous tail's next ptr to the thread's node. This allows enqueues to be wait free and avoid CAS retry storms which can greatly reduce performance under high contention
* There is a brief moment in time between swapping the queue's tail and linking the previous tail's next node in which the queue's state observed by a traversing lock holder could be inconsistent.
* Hence, a combining operation, is exited once a lock holder encounters this inconsistency.
*
* A lock holder applies its own op, then drains up to maxOps nodes, bounding the time it spends serving others.
*
* Waiters use an escalating backoff strategy (from spin waiting to periodically sleeping) by tracking
* the number of times they've missed acquiring the lock to make a local guess on how contended the structure or how deep their node is in the publication queue
* and therefore increase the time spent waiting on their request rather than continuously retrying the lock.
*
* Interrupts are deferred while waiting (parkNanos would otherwise return
* immediately and the node can't be abandoned once published) and restored on exit.
* Exceptions thrown by a combined op are rethrown in the owning thread as CombiningException.
*
* A lock holder can hop past the node of a stalled producer (preempted between its xchg and its link) that would otherwise block it from reaching every node behind it. To
* alleviate this, the lock holder that owns a node and hits a null next before having passed its own node hops to that node and keeps applying from there, since
* its own node is always reachable through its own next pointer. The hop never moves head and never unlinks to prevent, so the skipped region is reachable from the head
*/
abstract class CombinerCore<E> extends CombinerPad3<E> {

    /**
     * Default maximum number of operations a combiner can serve in one pass
     * */
    static final int DEFAULT_MAX_COMBINING_OPERATIONS = 100;

    /**
     * Number of available processors
     * */
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    static final boolean IS_UNIPROCESSOR = NCPU == 1;

    /**
     * Number of steps a spinning thread takes before yielding
     * */
    private static final int SPIN_STEPS = 9;

    /**
     * Number of steps a yielding thread takes before sleeping
     * */
    private static final int YIELD_STEPS = 4;

    /**
     * Maximum number of steps a waiting thread takes before sleeping
     * */
    private static final int MAX_WAIT_STEPS = SPIN_STEPS + YIELD_STEPS;

    /**
     * Sleep duration in nanoseconds
     * */
    private static final int SLEEP_DURATION_NANOS = 1_000;

    CombinerCore(E item) {
        super(item, DEFAULT_MAX_COMBINING_OPERATIONS, false);
        head = tail = new Node<>(null);
    }

    CombinerCore(E item, int maxCombiningOperations) {
        super(item, maxCombiningOperations, false);
        head = tail = new Node<>(null);
    }

    CombinerCore(E item, int maxCombiningOperations, boolean exposeMetrics) {
        super(item, maxCombiningOperations, exposeMetrics);
        head = tail = new Node<>(null);
    }

    public CombinerMetrics metrics() {
        if (!exposeMetrics) return CombinerMetrics.empty();
        lock.lock();
        try {
            return new CombinerMetrics(_total_ops, _passes, _combined, _passes == 0 ? 0 : (double) _total_ops / _passes);
        }finally {
            lock.unlock();
        }
    }


    <R> R tryCombining(Object op, Lock lock) {
        if (lock.tryLock()) {
            try {
                return doCombine(op);
            }finally {
                lock.unlock();
            }
        }

        Node<E> node = new Node<>(op);
        Node<E> prev = xchgTail(node);
        prev.srNext(node);

        int misses = 0;
        int steps = 1;
        boolean wasInterrupted = false;

        try {
            outer: for (;;) {
                if (steps <= SPIN_STEPS) {

                    int maxSpins = 1 << steps;
                    for (int spins = 0; spins++ < maxSpins;) {
                        Thread.onSpinWait();
                        if (node.laDone()) break outer;
                    }

                } else if (steps < MAX_WAIT_STEPS) {
                    Thread.yield();

                    if (node.laDone()) break;
                } else {
                    LockSupport.parkNanos(SLEEP_DURATION_NANOS);

                    if (!wasInterrupted && Thread.interrupted()) wasInterrupted = true; // clear interrupt flag

                    if (node.laDone()) break;
                }

                if (lock.tryLock()) {
                    try {
                        return doCombineNode(node);
                    }finally {
                        lock.unlock();
                    }
                }

                if (misses++ == 2) {
                    steps = Math.min(steps + 1, MAX_WAIT_STEPS);
                    misses = 0;
                }

            }
        }finally {
            if (wasInterrupted) Thread.currentThread().interrupt(); //reset interrupt flag
        }

        if (node.err != null) throw new CombiningException(node.err);
        else return (R) node.result;
    }


    abstract <R> R doCombine(Object o);

    abstract  <R> R doCombineNode(Node<E> node);

    abstract void combinePass();

    public void resetMetrics() {
        lock.lock();
        try {
            _total_ops = 0;
            _passes = 0;
            _combined = 0;
        }finally {
            lock.unlock();
        }
    }


    Node<E> xchgTail(Node<E> with) {
        return (Node<E>) TAIL.getAndSet(this, with);
    }


    static final VarHandle TAIL;

    static {
        var lookup = MethodHandles.lookup();
        try {
            TAIL = lookup.findVarHandle(CombinerCore.class, "tail", Node.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
