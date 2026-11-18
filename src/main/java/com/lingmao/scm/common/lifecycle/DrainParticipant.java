package com.lingmao.scm.common.lifecycle;

/** Stops admission first while allowing already owned work to finish. */
public interface DrainParticipant {
    void beginDrain();
    boolean drained();
    void forceStop();
}
