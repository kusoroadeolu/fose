package io.github.kusoroadeolu.fose;

import java.util.function.Function;

class BaseRequest<E> extends AbstractRequest<E> {
   public BaseRequest(Function<E, Object> operation) {
            super(operation);
        }
}