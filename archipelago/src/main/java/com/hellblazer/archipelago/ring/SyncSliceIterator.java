/*
 * Copyright (c) 2023. Hal Hildebrand, All Rights Reserved.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package com.hellblazer.archipelago.ring;

import com.hellblazer.archipelago.Link;
import com.hellblazer.archipelago.RouterImpl;
import com.hellblazer.archipelago.Utils;
import com.hellblazer.archipelago.membership.Member;
import com.hellblazer.archipelago.membership.SigningMember;
import com.hellblazer.cryptography.Entropy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * @author hal.hildebrand
 **/
public class SyncSliceIterator<Comm extends Link> {
    private static final Logger                                   log = LoggerFactory.getLogger(
    SyncSliceIterator.class);
    private final        RouterImpl.CommonCommunications<Comm, ?> comm;
    private final        String                                   label;
    private final        SigningMember                            member;
    private final        List<? extends Member>                   slice;
    private              Member                                   current;
    private              Iterator<? extends Member>               currentIteration;

    public SyncSliceIterator(String label, SigningMember member, List<? extends Member> slice,
                             RouterImpl.CommonCommunications<Comm, ?> comm) {
        assert member != null && slice != null && comm != null;
        this.label = label;
        this.member = member;
        this.slice = slice;
        this.comm = comm;
        Entropy.secureShuffle(slice);
        this.currentIteration = slice.iterator();
        log.debug("Slice: {}", slice.stream().map(m -> m.getId()).toList());
    }

    public <T> void iterate(BiFunction<Comm, Member, T> round, SyncSlicePredicateHandler<T, Comm> handler,
                            Runnable onComplete, ScheduledExecutorService scheduler, Duration frequency) {
        internalIterate(round, handler, onComplete, scheduler, frequency);
    }

    public <T> void iterate(BiFunction<Comm, Member, T> round, SyncSlicePredicateHandler<T, Comm> handler,
                            ScheduledExecutorService scheduler, Duration frequency) {
        iterate(round, handler, null, scheduler, frequency);
    }

    private <T> void internalIterate(BiFunction<Comm, Member, T> round, SyncSlicePredicateHandler<T, Comm> handler,
                                     Runnable onComplete, ScheduledExecutorService scheduler, Duration frequency) {
        Runnable proceed = () -> internalIterate(round, handler, onComplete, scheduler, frequency);

        Consumer<Boolean> allowed = allow -> proceed(allow, proceed, onComplete, scheduler, frequency);
        try (Comm link = next()) {
            if (link == null) {
                allowed.accept(handler.handle(Optional.empty(), link, slice.get(slice.size() - 1)));
                return;
            }
            log.trace("Iteration on: {} index: {} to: {} on: {}", label, current.getId(), link.getMember(),
                      member.getId());
            var result = round.apply(link, link.getMember());
            allowed.accept(handler.handle(Optional.ofNullable(result), link, link.getMember()));
        } catch (IOException e) {
            log.debug("Error closing", e);
        }
    }

    private Comm linkFor(Member m) {
        try {
            return comm.connect(m);
        } catch (Throwable e) {
            log.error("error opening connection to {}: {}", m.getId(),
                      (e.getCause() != null ? e.getCause() : e).getMessage());
        }
        return null;
    }

    private Comm next() {
        if (!currentIteration.hasNext()) {
            Entropy.secureShuffle(slice);
            currentIteration = slice.iterator();
        }
        current = currentIteration.next();
        return linkFor(current);
    }

    private void proceed(final boolean allow, Runnable proceed, Runnable onComplete, ScheduledExecutorService scheduler,
                         Duration frequency) {
        log.trace("Determining continuation for: {} final itr: {} allow: {} on: {}", label, !currentIteration.hasNext(),
                  allow, member.getId());
        if (!currentIteration.hasNext() && allow) {
            log.trace("Final iteration of: {} on: {}", label, member.getId());
            if (onComplete != null) {
                log.trace("Completing iteration for: {} on: {}", label, member.getId());
                onComplete.run();
            }
        } else if (allow) {
            log.trace("Proceeding for: {} on: {}", label, member.getId());
            scheduler.schedule(Utils.wrapped(proceed, log), frequency.toNanos(), TimeUnit.NANOSECONDS);
        } else {
            log.trace("Termination for: {} on: {}", label, member.getId());
        }
    }

    @FunctionalInterface
    public interface SyncSlicePredicateHandler<T, Comm> {
        boolean handle(Optional<T> result, Comm communications, Member member);
    }
}
