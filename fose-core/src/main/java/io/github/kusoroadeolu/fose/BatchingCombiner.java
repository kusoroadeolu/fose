package io.github.kusoroadeolu.fose;

import java.util.ArrayList;

//Experimental, will contain some old code
abstract class BatchingCombinerPad0<E> extends CombinerCore<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;

    public BatchingCombinerPad0(E item) {
        super(item);
    }
}

abstract class BatchingCombinerFields<E, Q> extends BatchingCombinerPad0<E> {
    final CombiningBatch<Q, Object> combiningBatch;
    final BatchHandler<E, Q, Object> handler;
    final ArrayList<Node<E>> waiters;

    BatchingCombinerFields(E item, BatchHandler<E, Q, Object> handler) {
        super(item);
        this.combiningBatch = new CombiningBatch<>(NCPU);
        this.handler = handler;
        waiters = new ArrayList<>();
    }
}

abstract class BatchingCombinerPad1<E, Q> extends BatchingCombinerFields<E, Q> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15;

    BatchingCombinerPad1(E item, BatchHandler<E, Q, Object> handler) {
        super(item, handler);
    }
}

public class BatchingCombiner<E, Q> extends BatchingCombinerPad1<E, Q> {

    BatchingCombiner(E item, BatchHandler<E, Q, Object> handler) {
        super(item, handler);
    }

    public <R> R accept(Q operation) {
        return super.tryCombining(operation, lock);
    }

    @Override
    <R> R doCombine(Object o) {
        this.combiningBatch.setRequest(0, (Q) o);
        combinePass();
        return notifyWaiters();
    }

    @Override
    <R> R doCombineNode(Node<E> node) {
        if (node.done) {
            if (node.err != null) throw new CombiningException(node.err);
            else return (R) node.result;
        }

        combiningBatch.setRequest(0, (Q) node.op);
        node.done = true; //mark as done
        combinePass();
        return notifyWaiters();
    }

    @Override
    void combinePass() {
        int index = 1;
        Node<E> prev = this.head;
        Node<E> curr;
        CombiningBatch<Q, Object> combiningBatch = this.combiningBatch;
        final E item = this.item;

        for (int ops = 0; ops < maxOps && (curr = prev.lvNext()) != null; ++ops) {

            if (!curr.done) {
                combiningBatch.setRequest(index++, (Q) curr.op);
                waiters.add(curr);
            }

            prev.next = null;
            prev = curr;
        }

        ++this._passes;
        if (head == prev) ++this._combined;

        head = prev;
        handler.process(combiningBatch, item); //TODO add exception handling for when process might throw
    }

    <R> R notifyWaiters() {
        CombiningBatch<Q, Object> combiningBatch = this.combiningBatch;
        int size = waiters.size();
        for (int index = 0; index < size; ++index) {
            int listIndex = index + 1;
            Node<E> waiter = waiters.get(index);

            if (combiningBatch.hasErr(listIndex)) waiter.err = combiningBatch.err(listIndex);
            else waiter.result = combiningBatch.result(listIndex);

            waiter.srDone();

            combiningBatch.remove(listIndex);
        }

        waiters.clear();

        if (combiningBatch.hasErr(0)) {
            var t = combiningBatch.err(0);
            combiningBatch.remove(0);
            throw new CombiningException(t);
        } else {
            var r = combiningBatch.result(0);
            combiningBatch.remove(0);
            return (R) r;
        }
    }
}
