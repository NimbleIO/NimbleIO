/*
 * Copyright 2015 The FireNio Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package test.others;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author wangkai
 */
public class TestLock {

    private Object lock = new Object();

    private AtomicBoolean lockCAS = new AtomicBoolean();

    private int locks = 0;

    private Thread owner = null;

    private AtomicBoolean releaseCAS = new AtomicBoolean();

    private AtomicBoolean unlockCAS = new AtomicBoolean();

    private boolean cas(AtomicBoolean cas) {
        return cas.compareAndSet(false, true);
    }

    private Thread currentThread() {
        return Thread.currentThread();
    }

    private Thread getOwner() {
        return owner;
    }

    public void lock() {
        if (getOwner() == currentThread()) {
            locks++;
            return;
        }

        if (cas(lockCAS)) {
            locks++;
            setOwner();
            return;
        }

        if (cas(unlockCAS)) {
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                }

            }
        }

    }

    private void releaseCas(AtomicBoolean cas) {
        cas.set(false);
    }

    private void setOwner() {
        owner = currentThread();
    }

    public void unlock() {

    }

}
