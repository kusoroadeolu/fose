package io.github.kusoroadeolu.fose;


import org.openjdk.jol.info.ClassLayout;

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
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;
}

class CombinerStateField extends CombinerPad0 {
    volatile int state = FREE;
    static final int FREE = 0;
}

class CombinerPad1 extends CombinerStateField {
    byte b0, b1, b2, b3;
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;
}

class CombinerWaiterField<E> extends CombinerPad1 {
    volatile Node tail;
}

class CombinerPad2<E> extends CombinerWaiterField<E> {
    byte b0, b1, b2, b3;
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;
}

class CombinerFields<E> extends CombinerPad2<E> {
    Node head;

    //metrics
    long _combined;
    long _passes;
    long _total_ops;
}

class CombinerPad3<E> extends CombinerFields<E> {
    byte b0, b1, b2, b3;
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;
}

class CombinerMiscFields<E> extends CombinerPad3<E> {
    final int spins;
    final int postSpins;
    final boolean exposeMetrics;
    final int maxOperationsPerPass;
    final E item;

    CombinerMiscFields(E item, int spins, int postParkSpins, boolean exposeMetrics, int maxOperationsPerPass) {
        this.item = item;
        this.spins = spins;
        this.postSpins = postParkSpins;
        this.exposeMetrics = exposeMetrics;
        if (maxOperationsPerPass < 1) throw new IllegalArgumentException("maxOperationsPerPass must be >= 1, got " + maxOperationsPerPass);
        this.maxOperationsPerPass = maxOperationsPerPass;
    }
}

class CombinerPad5<E> extends CombinerMiscFields<E> {
    byte b0, b1, b2, b3;
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;

    CombinerPad5(E item, int spins, int postParkSpins, boolean exposeMetrics, int maxOperationsPerPass) {

        super(item, spins, postParkSpins, exposeMetrics, maxOperationsPerPass);
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
public class Combiner<E> extends CombinerPad5<E> {


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
     * Enqueueing a node is an atomic exchange on the queue's tail,
     * followed by linking the previous tail's next pointer to the node
     * and making this visible by a volatile write to the node's op field.
     * There is a brief window between these two steps in which the queue
     * seen by a traversing combiner may be inconsistent.
     *
     * A node only becomes visible to the combiner once it's operation
     * field has been set so a combining pass ends
     * when the combiner sees a null operation. A pass is also capped at
     * maxOps so one combiner cannot get stuck serving others forever
     *
     * A thread may try to become the combiner when its node is first in the
     * queue (i.e. its predecessor is head, the last served node). This only
     * gives it the right to contend, not a guarantee of success, since threads
     * that are not enqueued may barge in and acquire the lock.
     *
     * Signalling parked threads uses a Dekker-like scheme similar to the one
     * in AQS. A to-be-parked thread publishes a PARKING status, then rechecks
     * whether its request has been applied or its node is first in the queue
     * before parking. After releasing the lock, the combiner
     * atomically clears the status & unparks the next waiter if it is PARKING.
     * It atomically clears the status of, and unparks, any served waiter that was
     * PARKING during its combining pass
     *
     * Accesses to operation, tail and state are volatile to ensure correct ordering
     * The fields are spread across a chain of padded superclasses to avoid
     * false sharing between state, tail, head and certain read only fields.
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
     * Is a uniprocessor system
     * */
    static final boolean UPC = Runtime.getRuntime().availableProcessors() == 1;

    /**
     * Max number of spins a spinning waiter can exhaust before parking
     * */
    static final int SPINS = 1 << 7;

    /**
     * Max number of spins a previously parked waiter can exhaust before reparking
     * */
    static final int POST_SPINS = 1 << 5;

    //Public constructors
    public Combiner(E item) {
        this(item, MAX_OPERATIONS_PER_PASS, false);
    }

    public Combiner(E item, int maxOperationsPerPass) {
        this(item, maxOperationsPerPass, false);
    }

    //For benchmarks
    Combiner(E item, int maxOperationsPerPass, boolean exposeMetrics) {
        this(item, maxOperationsPerPass, exposeMetrics, SPINS, POST_SPINS);
    }


    // For tests
    Combiner(E item, int maxOperationsPerPass, boolean exposeMetrics, int spins, int postParkSpins) {
        super(Objects.requireNonNull(item), spins, postParkSpins, exposeMetrics, maxOperationsPerPass);
        head = tail = new Node(IS_COMBINER);
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
    private <R> R submit(byte kind, Object op, Object arg) {
        Objects.requireNonNull(op);
        if (weakTryAcquire()) return combine(kind, op, arg); //we dont need a strong TTAS here
        return tryCombining(kind, op, arg);
    }

    private  <R> R combine(byte kind, Object op, Object arg) {
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
    private  <R> R combineEnqueued(Node node) {
        Node head = this.head;
        if (head == node) {
            combinePass();
        } else {
            release();
            notifyNextWaiter(head);
        }

        return result(node);
    }

    private void notifyNextWaiter(Node node) {
        Object op = node.operation;
        Thread w = (op != null && node.status == WAITING) ? node.waiter : null;
        if (w != null) {
            node.unsetWaitingStatus();
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
        Node node = head;
        final E item = this.item;
        Node[] waiters = null;
        int ops = 0;
        int waiting = 0;
        int m = maxOperationsPerPass;
        Object op;

        try {
            while (ops < m && (op = node.getOperationAcquire()) != null) {

                Node n = node.next; //made visible my acquire read of node.op

                try {
                    node.result = execute(node.kind, op, node.arg, item);
                    node.signal = SERVED; //make result visible to waiter
                } catch (Throwable t) {
                    node.result = t;
                    node.signal = ERR_OCCURRED; //make result visible to waiter

                }

                if (node.status == WAITING) {
                    if (waiters == null)
                        waiters = new Node[(int) (m - ops)];
                    waiters[waiting++] = node;
                }

                ops++;
                node = n;
            }

            recordMetrics(ops);
        }finally {
            if (node.getSignalPlain() != IS_COMBINER) node.signal = IS_COMBINER;

            head = node;
            release();

            //recheck after release to handle the issue of a missed handoff signal
            //if the waiter is unavailable after setting head = prev, the handoff signal cannot be lost
            notifyNextWaiter(node);

            if (waiters != null) {
                for (int i = 0; i < waiting; ++i) {
                    Node w = waiters[i];
                    if (w.status == WAITING) {
                        w.unsetWaitingStatus();
                        LockSupport.unpark(w.waiter);
                    }
                }
            }
        }
    }

    private  <R> R tryCombining(byte kind, Object op, Object arg) {
        boolean wasInterrupted = false;
        Node node = null, succ = null;
        Thread w = Thread.currentThread();
        int signal = 0;

        try {
            boolean canCombine = false;
            int spins = this.spins;
            boolean spinning = false;
            boolean refilled = false; //idea behind this is
            //if we realize we're meant to be the combiner but failed to acquire the lock
            //spin a bit more before parking cause our result might be served soon

            for (;;) {
                if (node != null && ((signal = node.signal) & 1) == 1) break;

                if (canCombine || succ == null || (canCombine = (signal == IS_COMBINER))) {
                    if (tryAcquire()) {
                        if (canCombine) return combineEnqueued(node);
                        else return combine(kind, op, arg);
                    }

                    if (canCombine && !refilled) {
                        if (spins < postSpins) spins = postSpins;
                        if (!w.isVirtual() && !UPC) spinning = true;
                        refilled = true;
                    }
                }

                if (succ == null) {
                    succ = new Node();
                } else if (node == null) {
                    node = xchgTail(succ);

                    node.kind = kind;
                    node.arg = arg;
                    node.next = succ;

                    node.operation = op; //make op and earlier writes visible

                } else if (spinning && spins > 0) {
                    --spins;
                    Thread.onSpinWait();
                } else if (node.status == 0) {

                    if (spins > 0 && !w.isVirtual() && !UPC) {
                        spinning = true;
                    } else {
                        node.waiter = w;
                        node.status = WAITING;
                        spinning = false;
                    }

                } else {
                    LockSupport.park(this);
                    if (!wasInterrupted && Thread.interrupted()) wasInterrupted = true;
                    node.clearStatus();                    // clear status & loop top rechecked prev.signal first
                    if (spins <= 0) spins = postSpins;
                }

            }
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt();
        }
        return result(node);
    }

    private <R> R result(Node node) {
        if (node.getSignalPlain() == ERR_OCCURRED) {
            Throwable t = (Throwable) node.result;
            cleanNode(node);
            throw new CombiningException(t);
        } else {
            Object o = node.result;
            cleanNode(node);
            return (R) o;
        }
    }

    private void cleanNode(Node node) {
        node.clearOperation();
        node.waiter = null;
        node.result = null;
        node.arg = null;
        node.next = null;
    }

    boolean tryAcquire() {
        if (state == FREE && (int) STATE.getAndAdd(this, 1) == FREE) {
            setExclusiveOwnerThread(Thread.currentThread());
            return true;
        }

        return false;
    }

    boolean weakTryAcquire() {
        if ((int) STATE.getOpaque(this) == FREE && (int) STATE.getAndAdd(this, 1) == FREE) {
            setExclusiveOwnerThread(Thread.currentThread());
            return true;
        }

        return false;
    }

    void release() {
        setExclusiveOwnerThread(null);
        state = 0;
    }

    private Node xchgTail(Node node) {
        return (Node) TAIL.getAndSet(this, node);
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
    static final VarHandle STATE;



    static {
        var lookup = MethodHandles.lookup();
        try {
            STATE = lookup.findVarHandle(Combiner.class, "state", int.class);
            TAIL = lookup.findVarHandle(Combiner.class, "tail", Node.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void main(String[] args) {
        var s = ClassLayout.parseClass(Combiner.class).toPrintable();
        System.out.println(s);
    }

}