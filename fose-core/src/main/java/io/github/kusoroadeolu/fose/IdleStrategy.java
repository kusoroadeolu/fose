package io.github.kusoroadeolu.fose;

import java.util.concurrent.locks.LockSupport;

public interface IdleStrategy {
    int idle(int idleCount);

    class SpinBackoffIdleStrategy implements IdleStrategy {

        private static final SpinBackoffIdleStrategy INSTANCE = new SpinBackoffIdleStrategy();
        private static final int STEPS = 9;

        @Override
        public int idle(int idleCount) {
//            if (idleCount < STEPS) {
//                int spins = 1 << (++idleCount);
//                for (int i = 0; i < spins; ++i) Thread.onSpinWait();
//                return idleCount;
//            }
            if (idleCount < 1024) {
                Thread.onSpinWait();
                return ++idleCount;
            } else return -1;

           // return -1;
        }

    }

    class SleepingIdleStrategy implements IdleStrategy {

        private static final SleepingIdleStrategy INSTANCE = new SleepingIdleStrategy();
        private static final int SLEEP_TIME_NS = 1000;

        @Override
        public int idle(int idleCount) {
            LockSupport.parkNanos(SLEEP_TIME_NS);
            return -1;
        }

    }

    static IdleStrategy spin() {
        return SpinBackoffIdleStrategy.INSTANCE;
    }

    static IdleStrategy sleep() {
        return SleepingIdleStrategy.INSTANCE;
    }
}
