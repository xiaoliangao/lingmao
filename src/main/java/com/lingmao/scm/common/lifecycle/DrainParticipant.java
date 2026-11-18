package com.lingmao.scm.common.lifecycle;

/** 先停止接新活，已经接下的让它做完。 */
public interface DrainParticipant {
    void beginDrain();
    boolean drained();
    void forceStop();
}
