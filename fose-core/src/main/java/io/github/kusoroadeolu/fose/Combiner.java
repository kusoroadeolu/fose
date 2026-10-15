package io.github.kusoroadeolu.fose;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;

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
    volatile Request<E> tail;
    final IdleStrategy strategy;

    public CombinerWaiterField(IdleStrategy strategy) {
        this.strategy = strategy;
    }
}

class CombinerPad2<E> extends CombinerWaiterField<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;

    public CombinerPad2(IdleStrategy strategy) {
        super(strategy);
    }
}

class CombinerFields<E> extends CombinerPad2<E> {
    Request<E> head;
    final E item;
    final int maxOps; //max operations per combining session
    int _uncombined;
    int _passes;

    public CombinerFields(E item, int maxOps, IdleStrategy strategy) {
        super(strategy);
        this.item = item;
        this.maxOps = maxOps;
    }
}

class CombinerPad3<E> extends CombinerFields<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15;


    public CombinerPad3(E item, int maxOps, IdleStrategy strategy) {
        super(item, maxOps, strategy);
    }
}

public class Combiner<E> extends CombinerPad3<E> {

    private static final int maxCombiningOperations = 100;
    private static final boolean isUniprocessor = Runtime.getRuntime().availableProcessors() == 1;

    public Combiner(E item) {
        super(item, maxCombiningOperations, IdleStrategy.spin());
        head = tail = new BaseRequest<>(null);
    }

    public Combiner(E item, IdleStrategy strategy) {
        super(item, maxCombiningOperations, strategy);
        head = tail = new BaseRequest<>(null);
    }

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }



    public  <R> R accept(Function<E, R> function) {
        var lock = this.lock;
        Function<E, Object> fn = (Function<E, Object>) function;
        if (lock.tryLock()) {
            try {
                return doCombineFunction(fn);
            }finally {
                lock.unlock();
            }
        }

        Request<E> req = new BaseRequest<>(fn);
        Request<E> prev = xchgTail(req);
        prev.srNext(req);

        int misses = 0;
        int steps = 1;
        for (;;) {
            if (awaitCombining(req, steps)) return (R) req.result();

            if (lock.tryLock()) {
                try {
                    return doCombineRequest(req);
                }finally {
                    lock.unlock();
                }
            }

            if (misses++ == 2) {
                steps = Math.min(steps + 1, 10);
                misses = 0;
            }
        }
    }


    <R> R doCombineFunction(Function<E, Object> fn) {
        final E item = this.item;
        R result = (R) fn.apply(item);
        combinePass();
        return result;
    }

    <R> R doCombineRequest(Request<E> request) {
        if (request.lpDone()) return (R) request.result();
        final E item = this.item;
        R result = (R) request.fn().apply(item);
        request.spDone();
        combinePass();
        return result;
    }

    boolean awaitCombining(Request<E> req, int steps) {
        boolean done = false;
        // final IdleStrategy strategy = this.strategy;
        int maxSpins = 1 << steps;
        for (int spins = 0; spins++ < maxSpins  && !(done = req.done());) {
            Thread.onSpinWait();
        }

        return done;
    }

    public int uncombined() {
        lock.lock();
        try {
            return _uncombined;
        }finally {
            lock.unlock();
        }
    }

    void resetUncombined() {
        lock.lock();
        try {
            _uncombined = 0;
        }finally {
            lock.unlock();
        }
    }

    void resetPasses() {
        lock.lock();
        try {
            _passes = 0;
        }finally {
            lock.unlock();
        }
    }

    public int passes() {
        lock.lock();
        try {
            return _passes;
        }finally {
            lock.unlock();
        }
    }

    void combinePass() {
        Request<E> prev = this.head;
        Request<E> curr;
        final E item = this.item;

        for (int ops = 0; ops < maxOps && (curr = prev.next()) != null; ++ops) {
            curr.doFn(item);
            prev.spNextNull();
            prev = curr;
        }

        ++this._passes;
        if (head == prev) ++this._uncombined;
        head = prev;
    }


    Request<E> xchgTail(Request<E> with) {
        return (Request<E>) TAIL.getAndSet(this, with);
    }


    static final VarHandle HEAD;
    static final VarHandle TAIL;

    static {
        var lookup = MethodHandles.lookup();
        try {
            HEAD = lookup.findVarHandle(Combiner.class, "head", Request.class);
            TAIL = lookup.findVarHandle(Combiner.class, "tail", Request.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
