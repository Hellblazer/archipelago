/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 *
 * Copyright (c) 2023. Hal Hildebrand, All Rights Reserved.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package com.hellblazer.rbc;

import com.codahale.metrics.Timer;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.hellblazer.archipelago.Router;
import com.hellblazer.archipelago.RouterImpl;
import com.hellblazer.archipelago.membership.Context;
import com.hellblazer.archipelago.membership.Member;
import com.hellblazer.archipelago.membership.SigningMember;
import com.hellblazer.archipelago.ring.SyncRingCommunications;
import com.hellblazer.cryptography.Entropy;
import com.hellblazer.cryptography.JohnHancock;
import com.hellblazer.cryptography.bloomFilters.BloomFilter;
import com.hellblazer.cryptography.hash.Digest;
import com.hellblazer.cryptography.hash.DigestAlgorithm;
import com.hellblazer.messaging.proto.*;
import com.hellblazer.rbc.comms.RbcServer;
import com.hellblazer.rbc.comms.ReliableBroadcast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.hellblazer.rbc.comms.RbcClient.getCreate;

/**
 * Content agnostic reliable broadcast of messages.
 *
 * @author hal.hildebrand
 */
public class ReliableBroadcaster {

    private static final Logger                                                      log     = LoggerFactory.getLogger(
    ReliableBroadcaster.class);
    private final        MessageAdapter                                              adapter;
    private final        Buffer                                                      buffer;
    private final        RouterImpl.CommonCommunications<ReliableBroadcast, Service> comm;
    private final        Context<Member>                                             context;
    private final        SyncRingCommunications<Member, ReliableBroadcast>           gossiper;
    private final        SigningMember                                               member;
    private final        RbcMetrics                                                  metrics;
    private final        Parameters                                                  params;
    private final        AtomicBoolean                                               started = new AtomicBoolean();
    private volatile     Consumer<Integer>                                           roundListener;
    private volatile     MessageHandler                                              channelHandler;

    public ReliableBroadcaster(Context<Member> context, SigningMember member, Parameters parameters,
                               Router communications, RbcMetrics metrics, MessageAdapter adapter) {
        this.params = parameters;
        this.context = context;
        this.member = member;
        this.metrics = metrics;
        buffer = new Buffer(context.timeToLive() + 1);
        this.comm = communications.create(member, context.getId(), new Service(),
                                          r -> new RbcServer(communications.getClientIdentityProvider(), metrics, r),
                                          getCreate(metrics), ReliableBroadcast.getLocalLoopback(member));
        gossiper = new SyncRingCommunications<>(context, member, this.comm);
        this.adapter = adapter;
    }

    public static MessageAdapter defaultMessageAdapter(Context<Member> context, DigestAlgorithm algo) {
        final Predicate<Any> verifier = any -> {
            SignedDefaultMessage sdm;
            try {
                sdm = any.unpack(SignedDefaultMessage.class);
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("Cannot unwrap", e);
            }
            var dm = sdm.getContent();
            var member = context.getMember(Digest.from(dm.getSource()));
            if (member == null) {
                return false;
            }
            return member.verify(JohnHancock.from(sdm.getSignature()), dm.toByteString());
        };
        final Function<Any, Digest> hasher = any -> {
            try {
                return JohnHancock.from(any.unpack(SignedDefaultMessage.class).getSignature()).toDigest(algo);
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("Cannot unwrap", e);
            }
        };
        Function<Any, List<Digest>> source = any -> {
            try {
                return Collections.singletonList(Digest.from(any.unpack(SignedDefaultMessage.class)
                                                                .getContent()
                                                                .getSource()));
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("Cannot unwrap", e);
            }
        };
        var sn = new AtomicInteger();
        BiFunction<SigningMember, Any, Any> wrapper = (m, any) -> {
            final var dm = DefaultMessage.newBuilder()
                                         .setNonce(sn.incrementAndGet())
                                         .setSource(m.getId().toDigeste())
                                         .setContent(any)
                                         .build();
            return Any.pack(SignedDefaultMessage.newBuilder()
                                                .setContent(dm)
                                                .setSignature(m.sign(dm.toByteString()).toSig())
                                                .build());
        };
        Function<AgedMessageOrBuilder, Any> extractor = am -> {
            try {
                return am.getContent().unpack(SignedDefaultMessage.class).getContent().getContent();
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("Cannot unwrap", e);
            }
        };
        return new MessageAdapter(verifier, hasher, source, wrapper, extractor);
    }

    public void clearBuffer() {
        log.warn("Clearing message buffer on: {}", member);
        buffer.clear();
    }

    public Member getMember() {
        return member;
    }

    public int getRound() {
        return buffer.round();
    }

    public void publish(Message message) {
        publish(message, false);
    }

    public void publish(Message message, boolean notifyLocal) {
        if (!started.get()) {
            return;
        }
        log.debug("publishing message on: {}", member.getId());
        AgedMessage m = buffer.send(Any.pack(message), member);
        if (notifyLocal) {
            deliver(Collections.singletonList(
            new Msg(Collections.singletonList(member.getId()), adapter.extractor.apply(m),
                    adapter.hasher.apply(m.getContent()))));
        }
    }

    public void register(Consumer<Integer> roundListener) {
        this.roundListener = roundListener;
    }

    public void registerHandler(MessageHandler listener) {
        channelHandler = listener;
    }

    public void removeHandler() {
        channelHandler = null;
    }

    public void removeRoundListener() {
        roundListener = null;
    }

    public void start(Duration duration, ScheduledExecutorService scheduler) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        var initialDelay = Entropy.nextBitsStreamLong(duration.toMillis());
        log.info("Starting Reliable Broadcaster[{}] for {}", context.getId(), member.getId());
        comm.register(context.getId(), new Service());
        scheduler.schedule(() -> oneRound(duration, scheduler), initialDelay, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping Reliable Broadcaster[{}] for {}", context.getId(), member.getId());
        buffer.clear();
        gossiper.reset();
        comm.deregister(context.getId());
    }

    private void deliver(List<Msg> newMsgs) {
        if (newMsgs.isEmpty()) {
            return;
        }
        final var current = channelHandler;
        if (current == null) {
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("Delivering: {} for context: {} on: {} ", newMsgs.stream().map(m -> m.hash).toList(),
                      context.getId(), member.getId());
        }
        try {
            current.message(context.getId(), newMsgs);
        } catch (Throwable e) {
            log.warn("Error in message handler on: {}", member.getId(), e);
        }
    }

    private Reconcile gossipRound(ReliableBroadcast link, int ring) {
        if (!started.get()) {
            return null;
        }
        //        log.trace("rbc gossiping[{}] from {} with {} on {}", buffer.round(), member.getId(), link.getMember().getId(),
        //                  ring);
        try {
            return link.gossip(MessageBff.newBuilder()
                                         .setRing(ring)
                                         .setDigests(buffer.forReconciliation().toBff())
                                         .build());
        } catch (Throwable e) {
            log.trace("rbc gossiping[{}] failed from {} with {} on {}", buffer.round(), member.getId(),
                      link.getMember().getId(), ring, e);
            return null;
        }
    }

    private void handle(Optional<Reconcile> result,
                        SyncRingCommunications.Destination<Member, ReliableBroadcast> destination, Duration duration,
                        ScheduledExecutorService scheduler, Timer.Context timer) {
        try {
            Reconcile gossip;
            try {
                gossip = result.get();
            } catch (NoSuchElementException e) {
                log.debug("null gossiping with {} on: {}", destination.member().getId(), member.getId(), e.getCause());
                return;
            }
            if (!gossip.getUpdatesList().isEmpty()) {
                log.debug("Received: {} updates from: {} on: {}", gossip.getUpdatesList().size(),
                          destination.member().getId(), member.getId());
            }
            buffer.receive(gossip.getUpdatesList());
            destination.link().update(ReconcileContext.newBuilder()
                                                      .setRing(destination.ring())
                                                      .addAllUpdates(
                                                      buffer.reconcile(BloomFilter.from(gossip.getDigests()),
                                                                       destination.member().getId()))
                                                      .build());
        } finally {
            if (timer != null) {
                timer.stop();
            }
            if (started.get()) {
                buffer.tick();
                if (roundListener != null) {
                    int gossipRound = buffer.round();
                    try {
                        roundListener.accept(gossipRound);
                    } catch (Throwable e) {
                        log.error("error sending round() to listener on: {}", member.getId(), e);
                    }
                }
                try {
                    scheduler.schedule(() -> oneRound(duration, scheduler), duration.toNanos(), TimeUnit.NANOSECONDS);
                } catch (RejectedExecutionException e) {
                    return;
                }
            }
        }
    }

    private void oneRound(Duration duration, ScheduledExecutorService scheduler) {
        if (!started.get()) {
            return;
        }

        var timer = metrics == null ? null : metrics.gossipRoundDuration().time();
        gossiper.execute((link, ring) -> gossipRound(link, ring),
                         (futureSailor, destination) -> handle(futureSailor, destination, duration, scheduler, timer));
    }

    @FunctionalInterface
    public interface MessageHandler {
        void message(Digest context, List<Msg> messages);
    }

    public record HashedContent(Digest hash, ByteString content) {
    }

    public record MessageAdapter(Predicate<Any> verifier, Function<Any, Digest> hasher,
                                 Function<Any, List<Digest>> source, BiFunction<SigningMember, Any, Any> wrapper,
                                 Function<AgedMessageOrBuilder, Any> extractor) {
    }

    public record Msg(List<Digest> source, Any content, Digest hash) {
    }

    public record Parameters(int bufferSize, int maxMessages, DigestAlgorithm digestAlgorithm, double falsePositiveRate,
                             int deliveredCacheSize) {
        public static Builder newBuilder() {
            return new Builder();
        }

        public static class Builder implements Cloneable {
            private int             bufferSize         = 1500;
            private int             deliveredCacheSize = 100;
            private DigestAlgorithm digestAlgorithm    = DigestAlgorithm.DEFAULT;
            private double          falsePositiveRate  = 0.00125;
            private int             maxMessages        = 500;

            public Parameters build() {
                return new Parameters(bufferSize, maxMessages, digestAlgorithm, falsePositiveRate, deliveredCacheSize);
            }

            @Override
            public Builder clone() {
                try {
                    return (Builder) super.clone();
                } catch (CloneNotSupportedException e) {
                    throw new IllegalStateException();
                }
            }

            public int getBufferSize() {
                return bufferSize;
            }

            public Builder setBufferSize(int bufferSize) {
                this.bufferSize = bufferSize;
                return this;
            }

            public int getDeliveredCacheSize() {
                return deliveredCacheSize;
            }

            public Builder setDeliveredCacheSize(int deliveredCacheSize) {
                this.deliveredCacheSize = deliveredCacheSize;
                return this;
            }

            public DigestAlgorithm getDigestAlgorithm() {
                return digestAlgorithm;
            }

            public Builder setDigestAlgorithm(DigestAlgorithm digestAlgorithm) {
                this.digestAlgorithm = digestAlgorithm;
                return this;
            }

            public double getFalsePositiveRate() {
                return falsePositiveRate;
            }

            public Builder setFalsePositiveRate(double falsePositiveRate) {
                this.falsePositiveRate = falsePositiveRate;
                return this;
            }

            public int getMaxMessages() {
                return maxMessages;
            }

            public Builder setMaxMessages(int maxMessages) {
                this.maxMessages = maxMessages;
                return this;
            }
        }

    }

    private record state(Digest hash, AgedMessage.Builder msg) {
    }

    public class Service implements Router.ServiceRouting {

        public Reconcile gossip(MessageBff request, Digest from) {
            Member predecessor = context.ring(request.getRing()).predecessor(member);
            if (predecessor == null || !from.equals(predecessor.getId())) {
                log.info("Invalid inbound messages gossip on {}:{} from: {} on ring: {} - not predecessor: {}",
                         context.getId(), member.getId(), from, request.getRing(),
                         predecessor == null ? "<null>" : predecessor.getId());
                return Reconcile.getDefaultInstance();
            }
            return Reconcile.newBuilder()
                            .addAllUpdates(buffer.reconcile(BloomFilter.from(request.getDigests()), from))
                            .setDigests(buffer.forReconciliation().toBff())
                            .build();
        }

        public void update(ReconcileContext reconcile, Digest from) {
            Member predecessor = context.ring(reconcile.getRing()).predecessor(member);
            if (predecessor == null || !from.equals(predecessor.getId())) {
                log.info("Invalid inbound messages reconcile on {}:{} from: {} on ring: {} - not predecessor: {}",
                         context.getId(), member.getId(), from, reconcile.getRing(),
                         predecessor == null ? "<null>" : predecessor.getId());
                return;
            }
            buffer.receive(reconcile.getUpdatesList());
        }
    }

    private class Buffer {
        private final DigestWindow       delivered;
        private final Semaphore          garbageCollecting = new Semaphore(1);
        private final int                highWaterMark;
        private final int                maxAge;
        private final AtomicInteger      round             = new AtomicInteger();
        private final Map<Digest, state> state             = new ConcurrentHashMap<>();
        private final Semaphore          tickGate          = new Semaphore(1);

        public Buffer(int maxAge) {
            this.maxAge = maxAge;
            highWaterMark = (params.bufferSize - (int) (params.bufferSize + ((params.bufferSize) * 0.1)));
            delivered = new DigestWindow(params.deliveredCacheSize, 3);
        }

        public void clear() {
            state.clear();
        }

        public BloomFilter<Digest> forReconciliation() {
            var biff = new BloomFilter.DigestBloomFilter(Entropy.nextBitsStreamLong(), params.bufferSize,
                                                         params.falsePositiveRate);
            state.keySet().forEach(k -> biff.add(k));
            if (state.size() > 0) {
                log.debug("for reconciliation:{} on: {}", state.size(), member);
            }
            return biff;
        }

        public void receive(List<AgedMessage> messages) {
            if (messages.size() == 0) {
                return;
            }
            log.debug("receiving: {} msgs on: {}", messages.size(), member);
            deliver(messages.stream()
                            .limit(params.maxMessages)
                            .map(am -> new state(adapter.hasher.apply(am.getContent()), AgedMessage.newBuilder(am)))
                            .filter(s -> !dup(s))
                            .filter(s -> adapter.verifier.test(s.msg.getContent()))
                            .map(s -> state.merge(s.hash, s, (a, b) -> a.msg.getAge() >= b.msg.getAge() ? a : b))
                            .map(s -> new Msg(adapter.source.apply(s.msg.getContent()), adapter.extractor.apply(s.msg),
                                              s.hash))
                            .filter(m -> delivered.add(m.hash, null))
                            .toList());
            gc();
        }

        public Iterable<? extends AgedMessage> reconcile(BloomFilter<Digest> biff, Digest from) {
            PriorityQueue<AgedMessage.Builder> mailBox = new PriorityQueue<>(Comparator.comparingInt(s -> s.getAge()));
            state.values().stream().filter(s -> !biff.contains(s.hash)).filter(s -> s.msg.getAge() < maxAge).forEach(
            s -> mailBox.add(s.msg));
            List<AgedMessage> reconciled = mailBox.stream().limit(params.maxMessages).map(b -> b.build()).toList();
            if (!reconciled.isEmpty()) {
                log.debug("reconciled: {} for: {} on: {}", reconciled.size(), from, member);
            }
            return reconciled;
        }

        public int round() {
            return round.get();
        }

        public AgedMessage send(Any msg, SigningMember member) {
            AgedMessage.Builder message = AgedMessage.newBuilder().setContent(adapter.wrapper.apply(member, msg));
            var hash = adapter.hasher.apply(message.getContent());
            state s = new state(hash, message);
            if (state.put(hash, s) == null) {
                log.debug("Add message:{} to state[{}] on: {}", hash, state.size(), member);
            }
            log.debug("Send message:{} on: {}", hash, member);
            return s.msg.build();
        }

        public int size() {
            return state.size();
        }

        public void tick() {
            round.incrementAndGet();
            if (!tickGate.tryAcquire()) {
                log.trace("Unable to acquire tick gate for: {} tick already in progress on: {}", context.getId(),
                          member);
                return;
            }
            try {
                var trav = state.entrySet().iterator();
                while (trav.hasNext()) {
                    var next = trav.next().getValue();
                    int age = next.msg.getAge();
                    if (age >= maxAge) {
                        trav.remove();
                        log.trace("GC'ing: {} age: {} > {} on: {}", next.hash, age + 1, maxAge, member.getId());
                    } else {
                        next.msg.setAge(age + 1);
                    }
                }
            } finally {
                tickGate.release();
            }
        }

        private boolean dup(state s) {
            if (s.msg.getAge() > maxAge) {
                log.debug("Rejecting message too old: {} age: {} > {} on: {}", s.hash, s.msg.getAge(), maxAge,
                          member.getId());
                return true;
            }
            var previous = state.get(s.hash);
            if (previous != null) {
                int nextAge = Math.max(previous.msg().getAge(), s.msg.getAge());
                if (nextAge > maxAge) {
                    state.remove(s.hash);
                } else if (previous.msg.getAge() != nextAge) {
                    previous.msg().setAge(nextAge);
                }
                log.debug("duplicate event: {} on: {}", s.hash, member.getId());
                return true;
            }
            final var contains = delivered.contains(s.hash);
            log.debug("received event: {} delivered: {} on: {}", s.hash, contains, member.getId());
            return contains;
        }

        private void gc() {
            if ((size() < highWaterMark) || !garbageCollecting.tryAcquire()) {
                return;
            }
            try {
                int startSize = state.size();
                if (startSize < highWaterMark) {
                    return;
                }
                log.trace("Compacting buffer: {} size: {} on: {}", context.getId(), startSize, member.getId());
                purgeTheAged();
                if (buffer.size() > params.bufferSize) {
                    log.warn("Buffer overflow: {} > {} after compact for: {} on: {} ", buffer.size(), params.bufferSize,
                             context.getId(), member);
                }
                int freed = startSize - state.size();
                if (freed > 0) {
                    log.debug("Buffer freed: {} after compact for: {} on: {} ", freed, context.getId(), member.getId());
                }
            } finally {
                garbageCollecting.release();
            }
        }

        private void purgeTheAged() {
            log.debug("Purging the aged of: {} buffer size: {}   on: {}", context.getId(), size(), member.getId());
            Queue<state> candidates = new PriorityQueue<>(
            Collections.reverseOrder((a, b) -> Integer.compare(a.msg.getAge(), b.msg.getAge())));
            candidates.addAll(state.values());
            var processing = candidates.iterator();
            while (processing.hasNext()) {
                var m = processing.next();
                if (m.msg.getAge() > maxAge) {
                    state.remove(m.hash);
                    log.trace("GC'ing: {} age: {} > {} on: {}", m.hash, m.msg.getAge() + 1, maxAge, member.getId());
                } else {
                    break;
                }
            }
        }
    }
}
