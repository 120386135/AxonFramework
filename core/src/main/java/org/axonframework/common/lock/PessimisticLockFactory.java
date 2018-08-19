/*
 * Copyright (c) 2010-2014. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.common.lock;

import org.axonframework.common.Assert;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Collections.newSetFromMap;
import static java.util.Collections.synchronizedMap;

/**
 * Implementation of a {@link LockFactory} that uses a pessimistic locking strategy. Calls to
 * {@link #obtainLock} will block until a lock could be obtained or back off based on the {@link BackoffParameters}
 * by throwing an exception. The later will cause the command to fail, but will allow the Thread to be released. If a
 * lock is obtained by a thread, that thread has guaranteed unique access.
 * <p/>
 * Each thread can hold the same lock multiple times. The lock will only be released for other threads when the lock
 * has been released as many times as it was obtained.
 * <p/>
 * This lock can be used to ensure thread safe access to a number of objects, such as Aggregates and Sagas.
 *
 * Back off properties with respect to acquiring locks can be configured though the {@link BackoffParameters}.
 *
 * @author Allard Buijze
 * @since 1.3
 */
public class PessimisticLockFactory implements LockFactory {

    private static final Set<PessimisticLockFactory> INSTANCES = newSetFromMap(synchronizedMap(new WeakHashMap<>()));

    private final ConcurrentHashMap<String, DisposableLock> locks = new ConcurrentHashMap<>();
    private final BackoffParameters backoffParameters;

    /**
     * Creates a new IdentifierBasedLock instance.
     * <p/>
     * Deadlocks are detected across instances of the IdentifierBasedLock.
     * This constructor specifies no back off from lock acquisition
     *
     * @apiNote Since the previous versions didn't support any backoff properties, this no-arg constructor creates a
     * {@link PessimisticLockFactory} with no backoff properties. This is however a poor default (the system will
     * very likely converge to a state where it no longer handles any commands any lock is held indefinitely.) In the
     * next major version of Axon sane defaults should be chosen and thus behavior will change. Should your setup rely
     * on the no-backoff behavior then you are advised to call {@link #PessimisticLockFactory(BackoffParameters)} with
     * explicitly specified {@link BackoffParameters}.
     */
    public PessimisticLockFactory() {
        this(new BackoffParameters(-1, -1, 100));
    }


    /**
     * Creates a new IdentifierBasedLock instance.
     * <p/>
     * Deadlocks are detected across instances of the IdentifierBasedLock.
     * Back off policy as by supplied {@link BackoffParameters}
     *
     * @param backoffParameters back off policy configuration
     */
    public PessimisticLockFactory(BackoffParameters backoffParameters) {
        this.backoffParameters = backoffParameters;
        INSTANCES.add(this);
    }

    /**
     * Obtain a lock for a resource identified by the given {@code identifier}. This method will block until a
     * lock was successfully obtained.
     * <p/>
     * Note: when an exception occurs during the locking process, the lock may or may not have been allocated.
     *
     * @param identifier the identifier of the lock to obtain.
     * @return a handle to release the lock. If the thread that releases the lock does not hold the lock
     * {@link IllegalMonitorStateException} is thrown
     */
    @Override
    public Lock obtainLock(String identifier) {
        boolean lockObtained = false;
        DisposableLock lock = null;
        while (!lockObtained) {
            lock = lockFor(identifier);
            lockObtained = lock.lock();
            if (!lockObtained) {
                locks.remove(identifier, lock);
            }
        }
        return lock;
    }

    private DisposableLock lockFor(String identifier) {
        DisposableLock lock = locks.get(identifier);
        while (lock == null) {
            locks.putIfAbsent(identifier, new DisposableLock(identifier));
            lock = locks.get(identifier);
        }
        return lock;
    }

    private static Set<Thread> threadsWaitingForMyLocks(Thread owner) {
        return threadsWaitingForMyLocks(owner, INSTANCES);
    }

    private static Set<Thread> threadsWaitingForMyLocks(Thread owner, Set<PessimisticLockFactory> locksInUse) {
        Set<Thread> waitingThreads = new HashSet<>();
        for (PessimisticLockFactory lock : locksInUse) {
            lock.locks.values().stream()
                    .filter(disposableLock -> disposableLock.isHeldBy(owner))
                    .forEach(disposableLock -> disposableLock.queuedThreads().stream()
                            .filter(waitingThreads::add)
                            .forEach(thread -> waitingThreads.addAll(threadsWaitingForMyLocks(thread, locksInUse))));
        }
        return waitingThreads;
    }

    /**
     * There are 3 values:
     *
     * acquireAttempts
     *  This used to specify the maxium number of attempts to obtain a lock before we back off
     *  (throwing a {@link LockAcquisitionFailedException}). A value of '-1' means unlimited attempts.
     *
     * maximumQueued
     *  Maximum number of queued threads we allow to try and obtain a lock, if another thread tries to obtain the lock
     *  after the limit is reached we back off (throwing a {@link LockAcquisitionFailedException}). A value of '-1'
     *  means the maximum queued threads is unbound / no limit.
     *  NOTE: This relies on approximation given by {@link ReentrantLock#getQueueLength()} so the effective limit may
     *  be higher then specified. Since this is a back off control this should be ok.
     *
     * spinTime
     *  Time permitted to try and obtain a lock per acquire attempt in milliseconds.
     *  NOTE: The spintime of the first attempt is always zero, so max wait time is approx
     *  (acquireAttempts - 1) * spinTime
     */
    public static final class BackoffParameters {
        public final int acquireAttempts;
        public final int maximumQueued;
        public final int spinTime;

        /**
         * A constructor that takes all values for all properties, please see the class level documentation for more
         * detail on these properties.
         * @param acquireAttempts   a positive number or -1 for trying indefinitely (no back off)
         * @param maximumQueued     a positive number or -1 for no limit (no back off)
         * @param spinTime          a non negative amount of milliseconds
         */
        public BackoffParameters(int acquireAttempts, int maximumQueued, int spinTime) {
            Assert.isTrue(
                    acquireAttempts > 0 || acquireAttempts == -1,
                    () -> "acquireAttempts needs to be a positive integer or -1, but was '"+acquireAttempts+"'"
            );
            this.acquireAttempts = acquireAttempts;
            Assert.isTrue(
                    maximumQueued > 0 || maximumQueued == -1,
                    () -> "maximumQueued needs to be a positive integer or -1, but was '"+maximumQueued+"'"
            );
            this.maximumQueued = maximumQueued;
            Assert.isFalse(
                    spinTime < 0,
                    () -> "spinTime needs to be a non negative integer, but was '"+spinTime+"'"
            );
            this.spinTime = spinTime;
        }

        public boolean hasAcquireAttemptLimit() {
            return acquireAttempts != -1;
        }

        public boolean hasAcquireQueueLimit() {
            return maximumQueued != -1;
        }

        public boolean maximumQueuedThreadsReached(int queueLength) {
            if(!hasAcquireQueueLimit()) {
                return false;
            }
            return queueLength >= maximumQueued;
        }
    }

    private class DisposableLock implements Lock {

        private final String identifier;
        private final PubliclyOwnedReentrantLock lock;
        private volatile boolean isClosed = false;

        private DisposableLock(String identifier) {
            this.identifier = identifier;
            this.lock = new PubliclyOwnedReentrantLock();
        }

        @Override
        public void release() {
            try {
                lock.unlock();
            } finally {
                disposeIfUnused();
            }
        }

        @Override
        public boolean isHeld() {
            return lock.isHeldByCurrentThread();
        }

        public boolean lock() {
            if (backoffParameters.maximumQueuedThreadsReached(lock.getQueueLength())) {
                throw new LockAcquisitionFailedException("Failed to acquire lock for aggregate identifier " + identifier + ": too many queued threads.");
            }
            try {
                if (!lock.tryLock(0, TimeUnit.NANOSECONDS)) {
                    int attempts = backoffParameters.acquireAttempts - 1;
                    do {
                        attempts--;
                        checkForDeadlock();
                        if(backoffParameters.hasAcquireAttemptLimit() && attempts < 1) {
                            throw new LockAcquisitionFailedException(
                                    "Failed to acquire lock for aggregate identifier(" + identifier + "), maximum attempts exceeded (" + backoffParameters.maximumQueued + ")"
                            );
                        }
                    } while (!lock.tryLock(backoffParameters.spinTime, TimeUnit.MILLISECONDS));
                }
            } catch (InterruptedException e) {
                throw new LockAcquisitionFailedException("Thread was interrupted", e);
            }
            if (isClosed) {
                lock.unlock();
                return false;
            }
            return true;
        }

        private void checkForDeadlock() {
            if (!lock.isHeldByCurrentThread() && lock.isLocked()) {
                for (Thread thread : threadsWaitingForMyLocks(Thread.currentThread())) {
                    if (lock.isHeldBy(thread)) {
                        throw new DeadlockException(
                                "An imminent deadlock was detected while attempting to acquire a lock"
                        );
                    }
                }
            }
        }

        private void disposeIfUnused() {
            if (lock.tryLock()) {
                try {
                    if (lock.getHoldCount() == 1) {
                        // we now have a lock. We can shut it down.
                        isClosed = true;
                        locks.remove(identifier, this);
                    }
                } finally {
                    lock.unlock();
                }
            }
        }

        public Collection<Thread> queuedThreads() {
            return lock.getQueuedThreads();
        }

        public boolean isHeldBy(Thread owner) {
            return lock.isHeldBy(owner);
        }

    }

    private static final class PubliclyOwnedReentrantLock extends ReentrantLock {

        private static final long serialVersionUID = -2259228494514612163L;

        @Override
        public Collection<Thread> getQueuedThreads() { // NOSONAR
            return super.getQueuedThreads();
        }

        public boolean isHeldBy(Thread thread) {
            return thread.equals(getOwner());
        }
    }
}
