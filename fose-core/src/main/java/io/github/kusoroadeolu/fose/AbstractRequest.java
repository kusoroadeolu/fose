package io.github.kusoroadeolu.fose;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Function;

abstract class AbstractRequest<E> implements Request<E> {
        Object result;
        Function<E, Object> fn; // to allow for handling errs? we should probably wrap the result in
        Request<E> next;
        boolean done;

        public AbstractRequest(Function<E, Object> fn) {
            this.fn = fn;
        }

        @Override
        public void doFn(E e) {
            if (!done) {
                try {
                    fn.apply(e);
                }catch (Exception _) {} //TODO add fo
                DONE.setRelease(this, true);
            }
        }

        @Override
        public Object result() {
            return result;
        }

        @Override
        public void spDone() {
            done = true;
        }

        @Override
        public void srNext(Request<E> request) {
            NEXT.setRelease(this, request);
        }

        @Override
        public Function<E, Object> fn() {
            return fn;
        }

        @Override
        public boolean done() {
            return (boolean) DONE.getAcquire(this);
        }

        @Override
        public boolean lpDone() {
            return done;
        }


        @Override
        public void spNextNull() {
            this.next = null;
        }

        @Override
        public Request<E> next() {
            return (Request<E>) NEXT.getVolatile(this);
        }

        static final VarHandle DONE;
        static final VarHandle NEXT;

        static {
            var lookup = MethodHandles.lookup();
            try {
                DONE = lookup.findVarHandle(AbstractRequest.class, "done", boolean.class);
                NEXT = lookup.findVarHandle(AbstractRequest.class, "next", Request.class);
            }catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
}