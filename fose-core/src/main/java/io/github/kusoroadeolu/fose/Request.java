package io.github.kusoroadeolu.fose;

import java.util.function.Function;

interface Request<E> {

    void doFn(E e);

    Object result();

    void spDone();

    void srNext(Request<E> request);

    Function<E, Object> fn();

    boolean laDone();

    boolean lpDone();

    void spNextNull();

    Request<E> lvNext();

}

