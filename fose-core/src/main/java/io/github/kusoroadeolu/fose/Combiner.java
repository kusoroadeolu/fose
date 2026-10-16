package io.github.kusoroadeolu.fose;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
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
}

class CombinerPad2<E> extends CombinerWaiterField<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;
}

class CombinerFields<E> extends CombinerPad2<E> {
    Request<E> head;
    final E item;
    final int maxOps; //max operations per combining session
    int _uncombined;
    int _passes;

    public CombinerFields(E item, int maxOps) {
        this.item = item;
        this.maxOps = maxOps;
    }
}

class CombinerPad3<E> extends CombinerFields<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15;


    public CombinerPad3(E item, int maxOps) {
        super(item, maxOps);
    }
}

public class Combiner<E> extends CombinerPad3<E> {

    private static final int MAX_COMBINING_OPERATIONS = 100;
    private static final int NCPU = Runtime.getRuntime().availableProcessors();
    private static final boolean UNIPROCESSOR = NCPU == 1;
    private static final int SPIN_STEPS = 10;
    private static final int MAX_STEPS = SPIN_STEPS + 4;
    private static final int PARK_NS = 1_000; //should this be more? maybe 1M, 1K ns performs alright, no need tbh

    public Combiner(E item) {
        super(item, MAX_COMBINING_OPERATIONS);
        head = tail = new BaseRequest<>(null);
    }

    //these lock/unlock api's are easy to misuse? should I avoid them. Honestly doesn't matter
    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }

    public  <R> R accept(Function<E, R> function) {
        var lock = this.lock;

        //if is uniprocessor, don't bother combining
        if (UNIPROCESSOR) {
            lock.lock();
            try {
               return function.apply(item);
            }finally {
                lock.unlock();
            }
        }

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

        outer: for (;;) {

            if (steps < SPIN_STEPS) {
                //spin from steps 1 to 10
                int maxSpins = 1 << steps;
                for (int spins = 0; spins++ < maxSpins;) {
                    Thread.onSpinWait();
                    if (req.laDone()) break outer;
                }

            } else if (steps < MAX_STEPS) { //yield from steps 10 to 13 (4 yields)
                Thread.yield();
                if (req.laDone()) break;
            } else {
                LockSupport.parkNanos(PARK_NS); //not a guaranteed 0.1ms park though, can be spuriously woken up or the park can be 1ms on some platforms
                //maybe should add a loop that handles how much time left to park, though probably not an issue
                if (req.laDone()) break;
            }

            if (lock.tryLock()) {
                try {
                    return doCombineRequest(req);
                }finally {
                    lock.unlock();
                }
            }

            if (misses++ == 2) {
                steps = Math.min(steps + 1, MAX_STEPS);
                misses = 0;
            }
        }

        return (R) req.result();
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

        for (int ops = 0; ops < maxOps && (curr = prev.lvNext()) != null; ++ops) {
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


    static final VarHandle TAIL;

    static {
        var lookup = MethodHandles.lookup();
        try {
            TAIL = lookup.findVarHandle(Combiner.class, "tail", Request.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
