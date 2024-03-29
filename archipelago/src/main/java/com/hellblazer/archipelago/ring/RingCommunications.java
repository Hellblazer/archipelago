/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.archipelago.ring;

import com.google.common.util.concurrent.ListenableFuture;
import com.hellblazer.archipelago.Link;
import com.hellblazer.archipelago.RouterImpl;
import com.hellblazer.archipelago.membership.Context;
import com.hellblazer.archipelago.membership.Member;
import com.hellblazer.archipelago.membership.Ring;
import com.hellblazer.archipelago.membership.SigningMember;
import com.hellblazer.cryptography.Entropy;
import com.hellblazer.cryptography.hash.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.hellblazer.archipelago.Utils.wrapped;

/**
 * @author hal.hildebrand
 */
public class RingCommunications<T extends Member, Comm extends Link> {
    private final static Logger                                   log            = LoggerFactory.getLogger(
    RingCommunications.class);
    final                Context<T>                               context;
    final                Executor                                 exec;
    final                SigningMember                            member;
    private final        RouterImpl.CommonCommunications<Comm, ?> comm;
    private final        Direction                                direction;
    private final        boolean                                  ignoreSelf;
    private final        Lock                                     lock           = new ReentrantLock();
    private final        List<iteration<T>>                       traversalOrder = new ArrayList<>();
    protected            boolean                                  noDuplicates   = false;
    volatile             int                                      currentIndex   = -1;

    public RingCommunications(Context<T> context, SigningMember member, RouterImpl.CommonCommunications<Comm, ?> comm,
                              Executor exec) {
        this(context, member, comm, exec, false);
    }

    public RingCommunications(Context<T> context, SigningMember member, RouterImpl.CommonCommunications<Comm, ?> comm,
                              Executor exec, boolean ignoreSelf) {
        this(Direction.SUCCESSOR, context, member, comm, exec, ignoreSelf);
    }

    public RingCommunications(Direction direction, Context<T> context, SigningMember member,
                              RouterImpl.CommonCommunications<Comm, ?> comm, Executor exec, boolean ignoreSelf) {
        assert direction != null && context != null && member != null && comm != null;
        this.direction = direction;
        this.context = context;
        this.member = member;
        this.comm = comm;
        this.exec = exec;
        this.ignoreSelf = ignoreSelf;
    }

    public <Q> void execute(BiFunction<Comm, Integer, ListenableFuture<Q>> round, Handler<T, Q, Comm> handler) {
        final var next = next(member.getId());
        if (next.member == null) {
            log.debug("No member for ring: {} on: {}", next.ring, member.getId());
            handler.handle(Optional.empty(), next);
            return;
        }
        try (Comm link = next.link) {
            log.debug("Executing ring: {} to: {} on: {}", next.ring, next.member.getId(), member.getId());
            execute(round, handler, next);
        } catch (IOException e) {
            log.debug("Error closing", e);
        }
    }

    public RingCommunications<T, Comm> noDuplicates() {
        noDuplicates = true;
        return this;
    }

    public void reset() {
        currentIndex = 0;
        traversalOrder.clear();
        log.trace("Reset on: {}", member.getId());
    }

    @Override
    public String toString() {
        return "RingCommunications [" + context.getId() + ":" + member.getId() + ":" + currentIndex + "]";
    }

    protected Logger getLog() {
        return log;
    }

    @SuppressWarnings("unchecked")
    List<iteration<T>> calculateTraversal(Digest digest) {
        var traversal = new ArrayList<iteration<T>>();
        var traversed = new TreeSet<T>();
        context.rings().forEach(ring -> {
            T successor = direction.retrieve(ring, digest, m -> {
                if (ignoreSelf && m.equals(member)) {
                    return Ring.IterateResult.CONTINUE;
                }
                if (!context.isActive(m)) {
                    return Ring.IterateResult.CONTINUE;
                }
                if (noDuplicates) {
                    if (traversed.add(m)) {
                        return Ring.IterateResult.SUCCESS;
                    } else {
                        return Ring.IterateResult.CONTINUE;
                    }
                }
                return Ring.IterateResult.SUCCESS;
            });
            traversal.add(new iteration<>(successor == null ? (T) member : successor, ring.getIndex()));
        });
        return traversal;
    }

    final Destination<T, Comm> next(Digest digest) {
        lock.lock();
        try {
            final var current = currentIndex;
            final var count = traversalOrder.size();
            if (count == 0 || current == count - 1) {
                traversalOrder.clear();
                traversalOrder.addAll(calculateTraversal(digest));
                assert traversalOrder.size() == context.getRingCount() : "Invalid traversal order size: "
                + traversalOrder.size() + " expected: " + context.getRingCount();
                Entropy.secureShuffle(traversalOrder);
                log.trace("New traversal order: {}:{} on: {}", context.getRingCount(), traversalOrder, member.getId());
            }
            int next = count == 0 ? 0 : (current + 1) % count;
            currentIndex = next;
            return linkFor(digest);
        } finally {
            lock.unlock();
        }
    }

    private <Q> void execute(BiFunction<Comm, Integer, ListenableFuture<Q>> round, Handler<T, Q, Comm> handler,
                             Destination<T, Comm> destination) {
        if (destination.link == null) {
            handler.handle(Optional.empty(), destination);
        } else {
            ListenableFuture<Q> futureSailor = round.apply(destination.link, destination.ring);
            if (futureSailor == null) {
                handler.handle(Optional.empty(), destination);
            } else {
                try {
                    futureSailor.addListener(wrapped(() -> {
                        handler.handle(Optional.of(futureSailor), destination);
                    }, log), exec);
                } catch (RejectedExecutionException e) {
                    // ignore
                }
            }
        }
    }

    private Destination<T, Comm> linkFor(Digest digest) {
        final var current = currentIndex;
        var successor = traversalOrder.get(current);
        try {
            final Comm link = comm.connect(successor.m);
            if (link == null) {
                log.trace("No connection to {} on: {}", successor.m == null ? "<null>" : successor.m.getId(),
                          member.getId());
            }
            return new Destination<>(successor.m, link, successor.ring);
        } catch (Throwable e) {
            log.trace("error opening connection to {}: {} on: {}", successor.m == null ? "<null>" : successor.m.getId(),
                      (e.getCause() != null ? e.getCause() : e).getMessage(), member.getId());
            return new Destination<>(successor.m, null, successor.ring);
        }
    }

    public enum Direction {
        PREDECESSOR {
            @Override
            public <T extends Member> T retrieve(Ring<T> ring, Digest hash, Function<T, Ring.IterateResult> test) {
                return ring.findPredecessor(hash, test);
            }

            @Override
            public <T extends Member> T retrieve(Ring<T> ring, T member, Function<T, Ring.IterateResult> test) {
                return ring.findPredecessor(member, test);
            }
        }, SUCCESSOR {
            @Override
            public <T extends Member> T retrieve(Ring<T> ring, Digest hash, Function<T, Ring.IterateResult> test) {
                return ring.findSuccessor(hash, test);
            }

            @Override
            public <T extends Member> T retrieve(Ring<T> ring, T member, Function<T, Ring.IterateResult> test) {
                return ring.findSuccessor(member, test);
            }
        };

        public abstract <T extends Member> T retrieve(Ring<T> ring, Digest hash, Function<T, Ring.IterateResult> test);

        public abstract <T extends Member> T retrieve(Ring<T> ring, T member, Function<T, Ring.IterateResult> test);
    }

    public record Destination<M, Q>(M member, Q link, int ring) {
    }

    private record iteration<T extends Member>(T m, int ring) {

        @Override
        public String toString() {
            return String.format("[%s,%s]", m == null ? "<null>" : m.getId(), ring);
        }

    }
}
