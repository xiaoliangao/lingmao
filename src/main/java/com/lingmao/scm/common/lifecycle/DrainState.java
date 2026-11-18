package com.lingmao.scm.common.lifecycle;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class DrainState {
    private final AtomicBoolean refusing = new AtomicBoolean();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile boolean ready;
    public boolean refusing() { return refusing.get(); }
    public void refuse() { refusing.set(true); ready = false; }
    public void initialized() { if (!refusing.get()) ready = true; }
    public boolean ready() { return ready && !refusing.get(); }
    public int inFlight() { return requests.get(); }
    public boolean enter() {
        if (!ready || refusing.get()) return false;
        requests.incrementAndGet();
        if (refusing.get()) { requests.decrementAndGet(); return false; }
        return true;
    }
    public void leave() { requests.decrementAndGet(); }
}
