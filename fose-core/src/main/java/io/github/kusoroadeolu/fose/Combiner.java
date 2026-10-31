package io.github.kusoroadeolu.fose;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.locks.AbstractOwnableSynchronizer;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.github.kusoroadeolu.fose.Node.*;

class CombinerPad0 extends AbstractOwnableSynchronizer {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15;
}

class CombinerStateField extends CombinerPad0 {
    volatile long state = FREE;
    static final long FREE = 0;
}

class CombinerPad1 extends CombinerStateField {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;
}

class CombinerWaiterField<E> extends CombinerPad1 {
    volatile Node<E> tail;
}

class CombinerPad2<E> extends CombinerWaiterField<E> {
    byte f0, f1, f2, f3; //extra padding to fill in the 4 byte gap
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;
}

class CombinerFields<E> extends CombinerPad2<E> {
    volatile Node<E> head;
    final E item;

    //metrics
    long _combined;
    long _passes;
    long _total_ops;

    public CombinerFields(E item) {
        this.item = item;
    }
}

class CombinerPad3<E> extends CombinerFields<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;


    public CombinerPad3(E item) {
        super(item);
    }
}

class CombinerSequenceField<E> extends CombinerPad3<E> {
    long currSeq;

    public CombinerSequenceField(E item) {
        super(item);
    }
}


class CombinerPad4<E> extends CombinerSequenceField<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;


    public CombinerPad4(E item) {
        super(item);
    }
}



/**
 * A synchronization primitive that lets threads apply operations to a
 * shared object on each other's behalf.
 *
 * <p>In the uncontended case, this primitive behaves no differently from
 * a standard lock. When contention is detected, however, threads can ask
 * the current lock holder to apply their operations for them, then wait
 * for the result, periodically trying to become the lock holder if their
 * operations have not been applied yet.
 *
 * <p>An example usage of this class might look like this:
 * <pre> {@code
 * class ThreadSafePriorityQueue<E> {
 *     private final Combiner<PriorityQueue<E>> combiner =
 *         new Combiner<>(new PriorityQueue<>());
 *
 *     public void push(E e) {
 *         combiner.accept(queue -> queue.offer(e));
 *     }
 *
 *     public E popMin() {
 *         return combiner.apply(queue -> queue.poll());
 *     }
 * }}</pre>
 *
 * <p>An operation may run on a different thread than the one that
 * submitted it, so it should not depend on thread-local state. It should
 * also be short, since a combiner runs other threads' operations back to
 * back and a slow one delays everyone queued behind it.
 *
 * <p>This class is not reentrant: calling a combining operation from
 * within another combining operation can deadlock. It also makes no
 * strict fairness guarantees, as threads may barge in and acquire the
 * lock ahead of queued waiters.
 *
 * <p>Waiting for an operation to be applied is uninterruptible. If a
 * thread is interrupted while waiting, the interrupt is deferred and its
 * status is restored on return.
 *
 * <p>Exceptions thrown by an operation are rethrown in the submitting
 * thread as {@link CombiningException}.
 *
 * @param <E> the type of the shared object
 * @author kusoroadeolu
 */
@SuppressWarnings("unchecked")
public class Combiner<E> extends CombinerPad4<E> {


    /*
     * The idea of flat combining was introduced in the 2010 SPAA paper "Flat
     * Combining and the Synchronization-Parallelism Tradeoff" (Hendler, Incze,
     * Shavit, Tzafrir).
     *
     * Flat combining is an alternative to fine-grained locking for making a
     * sequential structure thread-safe, while minimizing the synchronization
     * overhead and cache coherence traffic incurred when acquiring a lock.
     *
     * The basic idea is that when a thread finds the lock contended, it can
     * request that its operation be applied by the current combiner instead
     * of waiting to apply it itself.
     *
     * Enqueueing a node is a CAS on the queue's tail, followed by linking the
     * previous tail's next pointer to the node. There is a brief window between
     * these two steps in which the queue seen by a traversing combiner may be
     * inconsistent. A node only becomes visible to the combiner once the
     * previous tail's next pointer is linked to it, so a combining pass ends
     * when the combiner sees a null next pointer. A pass is also capped at
     * maxOps so one combiner cannot get stuck serving others forever.
     *
     * A thread may try to become the combiner when its node is first in the
     * queue (i.e. its predecessor is head, the last served node). This only
     * gives it the right to contend, not a guarantee of success, since threads
     * that are not enqueued may barge in and acquire the lock.
     *
     * To avoid unnecessary parks, waiters near the head of the queue (judged by
     * how far their sequence number is ahead of the last served operation) use
     * an escalating backoff, from spinning to parking. Waiters further from
     * the head, and virtual threads, park immediately.
     *
     * Signalling parked threads uses a Dekker-like scheme similar to the one
     * in AQS. A to-be-parked thread publishes a PARKING status, then rechecks
     * whether its request has been applied or its node is first in the queue
     * before parking. After releasing the lock, the combiner
     * atomically clears the status & unparks the next waiter if it is PARKING.
     * It atomically clears the status of, and unparks, any served waiter that was
     * PARKING during its combining pass
     *
     * Accesses to head (except under the lock), tail and state are volatile
     * while currSeq uses opaque accesses, which is fine since it is only a
     * heuristic for choosing a thread's initial wait strategy and isn't needed for
     * correctness.
     *
     * The fields are spread across a chain of padded superclasses to avoid
     * false sharing between state, tail, head and currSeq.
     *
     * Interrupts are deferred while waiting (park would otherwise return
     * immediately, and a node cannot be abandoned once published) and restored
     * on exit. Exceptions thrown by a combined op are rethrown in the owning
     * thread as CombiningException.
     *
     * References:
     * [1] Flat Combining and the Synchronization-Parallelism Tradeoff
     *     (Hendler, Incze, Shavit, Tzafrir)
     * [2] Revisiting the combining synchronization technique
     *     (Fatourou, Kallimanis)
     */

    /**
     * Default maximum number of operations a combiner can serve in one pass
     * */
    static final int MAX_OPERATIONS_PER_PASS = 25;

    /**
     * Number of available cpus
     * */
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * Max number of waiters in the queue that are allowed to spin
     * */
    static final int MAX_SPINNING_WAITERS = (int) (NCPU * 0.6);

    /**
     * Max number of spins a spinning waiter can exhaust before parking
     * */
    static final int SPINS = 1 << 10;

    /**
     * Max number of spins a previously parked waiter can exhaust before reparking
     * */
    static final int POST_PARK_SPINS = 1 << 7;

    final int spins;
    final int postParkSpins;
    final boolean exposeMetrics;
    final int maxOperationsPerPass;

    //Public constructors
    public Combiner(E item) {
        this(item, MAX_OPERATIONS_PER_PASS, false);
    }

    public Combiner(E item, int maxOperationsPerPass) {
        this(item, maxOperationsPerPass, false);
    }

    //For benchmarks
    Combiner(E item, int maxOperationsPerPass, boolean exposeMetrics) {
        this(item, maxOperationsPerPass, exposeMetrics, SPINS, POST_PARK_SPINS);
    }


    // For tests
    Combiner(E item, int maxOperationsPerPass, boolean exposeMetrics, int spins, int postParkSpins) {
        super(Objects.requireNonNull(item));
        this.spins = spins;
        this.postParkSpins = postParkSpins;
        this.exposeMetrics = exposeMetrics;
        if (maxOperationsPerPass < 1) throw new IllegalArgumentException("maxOperationsPerPass must be >= 1, got " + maxOperationsPerPass);
        this.maxOperationsPerPass = maxOperationsPerPass;
        head = tail = new Node<>(-1, null, null);
    }

    // Public APIs

    /**
     * Applies the given operation to the shared object while holding exclusive
     * access, either on the calling thread or, under contention, on another
     * thread, and returns its result.
     *
     * @param operation the operation to apply; may run on a different thread
     * @param <R> the result type
     * @return the value returned by the operation
     * @throws CombiningException if the operation throws
     * @throws NullPointerException if operation is null
     */
    public <R> R apply(Function<? super E, ? extends R> operation) {
        return submit(FN, operation, null);
    }

    /**
     * Similar to {@link #apply(Function)} for operations with no result.
     * Same threading and exception behavior.
     */
    public void accept(Consumer<? super E> operation) {
        submit(CONSUMER, operation, null);
    }

    /**
     * Applies the given operation to the shared object and the given argument
     * while holding exclusive access, either on the calling thread or, under
     * contention, on another thread, and returns its result.
     *
     * @param <A> the argument type
     * @param <R> the result type
     * @param arg the second argument passed to the operation
     * @param operation the operation to apply; may run on a different thread
     * @return the value returned by the operation
     * @throws CombiningException if the operation throws; the original
     *         exception is the cause
     * @throws NullPointerException if operation is null
     */
    public <A, R> R apply(A arg, BiFunction<? super E, ? super A, ? extends R> operation) {
        return submit(BI_FN, operation, arg);
    }
    /**
     * Similar to {@link #apply(Object, BiFunction)} for operations with no result.
     * Same threading and exception behavior.
     */
    public <A> void accept(A arg, BiConsumer<? super E, ? super A> operation) {
        submit(BI_CONSUMER, operation, arg);
    }

    //Internals & Helpers
    private <R> R submit(int kind, Object op, Object arg) {
        Objects.requireNonNull(op);
        if (acquire()) return combine(kind, op, arg);
        return tryCombining(kind, op, arg);
    }

    private  <R> R combine(int kind, Object op, Object arg) {
        final E item = this.item;
        Object result;

        try {
            result = execute(kind, op, arg, item);
        }catch (Throwable t) {
            throw new CombiningException(t);
        } finally {
            combinePass();
        }

        return (R) result;
    }


    @SuppressWarnings("unchecked")
    private static <E> Object execute(int kind, Object op, Object arg, E item) {
        return switch (kind) {
            case FN -> ((Function<E, ?>) op).apply(item);
            case BI_FN -> ((BiFunction<E, Object, ?>) op).apply(item, arg);

            case CONSUMER -> {
                ((Consumer<E>) op).accept(item);
                yield null;
            }

            default -> {
                ((BiConsumer<E, Object>) op).accept(item, arg);
                yield null;
            }
        };
    }

    //Tries to run its own op, if it is not at the head
    //of the queue, its op has been applied, and we notify the next waiter
    //otherwise, combine pass
    private  <R> R combineEnqueued(Node<E> prev, Node<E> node) {
        Node<E> head = getHeadPlain();
        if (head == prev) {
            combinePass();
        } else {
            release();
            notifyNextWaiter(head);
        }

        return result(node);
    }

    private void notifyNextWaiter(Node<E> prev) {
        Node<E> next = prev.next;
        Thread w = (next != null && next.status == Node.PARKING) ? next.waiter : null;
        if (w != null) {
            next.unsetStatus(PARKING);
            LockSupport.unpark(w);
        }
    }

    // walks the queue applying operations on the behalf of other
    // thread until we reach a null node or reach hte max combining limit
    // if we come across a parked waiter we store their node in a waiter array
    // after combining we handoff to the next eligible thread
    // while also handling the issue of a lost wakeup (see below)
    // and unpark parked threads we came across during our pass
    private void combinePass() {
        //assert Thread.currentThread() == getOwner();
        Node<E> prev = getHeadPlain();
        Node<E> curr;
        final E item = this.item;
        Node<E>[] waiters = null;
        int ops = 0;
        int waiting = 0;
        int m = maxOperationsPerPass;

        try {
            while (ops < m && (curr = prev.next) != null) {

                try {
                    curr.result = execute(curr.kind, curr.op, curr.arg, item);
                } catch (Throwable t) {
                    curr.err = t;
                }

                prev.next = null; //make result visible to waiter

                if (curr.status == Node.PARKING) {
                    if (waiters == null)
                        waiters = new Node[(int) (maxOperationsPerPass - ops)];
                    waiters[waiting++] = curr;
                }

                updateSeq(curr.seq); //curr.seq made visible by read of pred.next, a weak write is also alright here

                ops++;
                prev = curr;
            }

            recordMetrics(ops);
        }finally {
            head = prev;
            release();

            //recheck after release to handle the issue of a missed handoff signal
            //if the waiter is unavailable after setting head = prev, the handoff signal cannot be lost
            notifyNextWaiter(prev);

            if (waiters != null) {
                for (int i = 0; i < waiting; ++i) {
                    Node<E> w = waiters[i];
                    if (w.status == Node.PARKING) {
                        w.unsetStatus(Node.PARKING);
                        LockSupport.unpark(w.waiter);
                    }
                }
            }

        }
    }

    private  <R> R tryCombining(int kind, Object op, Object arg) {
        boolean wasInterrupted = false;
        Node<E> node = null, prev = null;
        Thread w = null;

        try {
            boolean first = false;
            long seenSeq;
            long remSpins = spins;
            boolean spin = false;
            int status;


            for (;;) {
                if (prev != null && prev.next == null) break;

                if (first || prev == null || (first = (prev == head))) {
                    if (acquire()) {
                        if (first) return combineEnqueued(prev, node);
                        else return combine(kind, op, arg);
                    }
                }

                if (node == null) {
                    node = new Node<>(kind, op, arg);
                } else if (prev == null) {
                    var t = tail;
                    long seq = t.seq;
                    node.seq = seq + 1;
                    node.waiter = w = Thread.currentThread();

                    if (!casTail(t, node)) {
                        node.waiter = null;
                        continue;
                    }

                    prev = t;
                    prev.next = node;
                } else if (spin && remSpins > 0) {
                    --remSpins;
                    Thread.onSpinWait();
                } else if ((status = node.status) == 0) {
                    int s;

                    seenSeq = currSeq();
                    node.status = s = !w.isVirtual() && shouldSpin(node, seenSeq) ?
                            Node.SPINNING : Node.PARKING;

                    if (s == Node.SPINNING && remSpins <= 0)
                        remSpins = postParkSpins;
                    //try to spin briefly if we've exhausted
                    //our spin budget but are near the head of the queue
                    //usually, a thread takes this branch after it's been unparked and was previously a spinner


                    spin = (s == SPINNING);

                } else if (status == Node.SPINNING) {
                    node.status = Node.PARKING; // budget spent, park
                    spin = false;
                } else {
                    LockSupport.park(this);
                    if (!wasInterrupted && Thread.interrupted()) wasInterrupted = true;
                    node.clearStatus();                    // clear status & loop top rechecked prev.next/head first
                }

            }
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt();
        }

        return result(node);
    }

    private boolean shouldSpin(Node<E> node, long seenSeq) {
        long s = node.seq;
        return (s - seenSeq) <= MAX_SPINNING_WAITERS;
    }

    private <R> R result(Node<E> node) {
        if (node.err != null) {
            Throwable t = node.err;
            cleanNode(node);
            throw new CombiningException(t);
        } else {
            Object o = node.result;
            cleanNode(node);
            return (R) o;
        }
    }

    private void cleanNode(Node<E> node) {
        node.err = null;
        node.op = null;
        node.waiter = null;
        node.result = null;
        node.arg = null;
    }

    boolean acquire() {
        if (state == FREE && (long) STATE.getAndAdd(this, 1) == FREE) {
            setExclusiveOwnerThread(Thread.currentThread());
            return true;
        }

        return false;
    }

    void release() {
        setExclusiveOwnerThread(null);
        state = 0;
    }

    private Node<E> getHeadPlain() {
        return (Node<E>) HEAD.get(this);
    }

    private long currSeq() {
        return (long) CURR_SEQ.getOpaque(this);
    }

    private void updateSeq(long seq) {
        CURR_SEQ.setOpaque(this, seq);
    }

    private boolean casTail(Node<E> from, Node<E> to) {
        return tail == from && TAIL.compareAndSet(this, from, to);
    }



    // Inspection & Debuggability
    /**
     * Queries whether this combiner's lock is currently held by any thread.
     *
     * <p>This method is designed for monitoring system state, not for
     * synchronization control. The result may be stale by the time it
     * returns.
     *
     * @return {@code true} if some thread currently holds the lock
     */
    public boolean isLocked() {
        return state != FREE;
    }

    /**
     * Queries whether the calling thread currently holds the lock.
     *
     * <p>Note that an operation submitted by one thread may be run by another,
     * so this reports whether the <em>calling thread</em> is the active
     * combiner, not whether its own operation is running. Inside an operation,
     * it returns {@code true} on whichever thread is executing it, and
     * {@code false} on the submitting thread if that thread is just waiting.
     * It is mainly useful for assertions, for example to check that a
     * combining operation is not being called from within another one, since
     * this class is not reentrant.
     *
     * @return {@code true} if the calling thread holds the lock
     */
    public boolean isHeldByCurrentThread() {
        return getExclusiveOwnerThread() == Thread.currentThread();
    }

    /**
     * Returns the thread that currently holds the lock, or {@code null} if
     * it is not held.
     *
     * <p>The owner is the thread currently combining, which may be running
     * operations submitted by other threads. This method is best-effort and
     * intended for debugging and monitoring: the lock state and owner are read
     * separately, so it may briefly return {@code null} just after the lock is
     * acquired or a stale thread just after it is released.
     *
     * @return the owner, or {@code null} if the lock is not held
     */
    public Thread getOwner() {
        return state == FREE ? null : getExclusiveOwnerThread();
    }

    /**
     * Returns an estimate of the number of operations waiting to be applied.
     *
     * <p>The value is computed from internal sequence numbers read without
     * synchronization, so it is only a snapshot of a moving target and may be
     * slightly off while threads are enqueuing or being served. It is designed
     * for monitoring and diagnostics, not for synchronization control.
     *
     * @return the estimated number of queued operations, never negative
     */
    public int getQueueLength() {
        return (int) Math.max(0, tail.seq - currSeq());
    }

    /**
     * Queries whether any operations are estimated to be waiting to be
     * applied.
     *
     * <p>Because this is based on {@link #getQueueLength()}, a {@code true}
     * result does not guarantee that any operation is still waiting by the
     * time the caller acts on it. Intended for monitoring only.
     *
     * @return {@code true} if the estimated queue length is above zero
     */
    public boolean hasQueuedThreads() {
        return getQueueLength() > 0;
    }
    @Override
    public String toString() {
        Thread o = getOwner();
        return super.toString() + (o == null
                ? "[Unlocked]"
                : "[Locked by thread " + o.getName() + "]");
    }

    private void recordMetrics(int ops) {
        if (exposeMetrics) {
            ++this._passes;
            if (ops > 0) {
                ++this._combined;
                _total_ops += ops;
            }
        }
    }

    static final VarHandle TAIL;
    static final VarHandle HEAD;
    static final VarHandle STATE;
    static final VarHandle CURR_SEQ;



    static {
        var lookup = MethodHandles.lookup();
        try {
            CURR_SEQ = lookup.findVarHandle(Combiner.class, "currSeq", long.class);
            HEAD = lookup.findVarHandle(Combiner.class, "head", Node.class);
            STATE = lookup.findVarHandle(Combiner.class, "state", long.class);
            TAIL = lookup.findVarHandle(Combiner.class, "tail", Node.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

}