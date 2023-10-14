/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.rbc.sync;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hellblazer.archipelago.*;
import com.hellblazer.archipelago.membership.Context;
import com.hellblazer.archipelago.membership.Member;
import com.hellblazer.archipelago.membership.SigningMember;
import com.hellblazer.archipelago.membership.impl.SigningMemberImpl;
import com.hellblazer.cryptography.Entropy;
import com.hellblazer.cryptography.hash.Digest;
import com.hellblazer.cryptography.hash.DigestAlgorithm;
import com.hellblazer.rbc.RbcMetrics;
import com.hellblazer.rbc.RbcMetricsImpl;
import com.hellblazer.rbc.ReliableBroadcaster;
import com.hellblazer.test.proto.ByteMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 */
public class RbcTest {

    private static final ReliableBroadcaster.Parameters.Builder parameters     = ReliableBroadcaster.Parameters.newBuilder()
                                                                                                               .setMaxMessages(
                                                                                                               1000)
                                                                                                               .setFalsePositiveRate(
                                                                                                               0.00125)
                                                                                                               .setBufferSize(
                                                                                                               5000);
    private final        List<Router>                           communications = new ArrayList<>();
    private final        AtomicInteger                          totalReceived  = new AtomicInteger(0);
    private              List<ReliableBroadcaster>              messengers;

    @AfterEach
    public void after() {
        if (messengers != null) {
            messengers.forEach(e -> e.stop());
        }
        communications.forEach(e -> e.close(Duration.ofMillis(10)));
    }

    @Test
    public void broadcast() throws Exception {
        MetricRegistry registry = new MetricRegistry();

        List<SigningMember> members = IntStream.range(0, 100)
                                               .mapToObj(i -> Utils.getMember(i))
                                               .map(cpk -> new SigningMemberImpl(cpk))
                                               .map(e -> (SigningMember) e)
                                               .toList();

        Context<Member> context = Context.newBuilder().setCardinality(members.size()).build();
        RbcMetrics metrics = new RbcMetricsImpl(context.getId(), "test", registry);
        members.forEach(m -> context.activate(m));

        var exec = Executors.newVirtualThreadPerTaskExecutor();
        final var prefix = UUID.randomUUID().toString();
        final var authentication = ReliableBroadcaster.defaultMessageAdapter(context, DigestAlgorithm.DEFAULT);
        messengers = members.stream().map(node -> {
            var comms = new LocalServer(prefix, node, exec).router(
            ServerConnectionCache.newBuilder().setTarget(30).setMetrics(new ServerConnectionCacheMetricsImpl(registry)),
            exec);
            communications.add(comms);
            comms.start();
            return new ReliableBroadcaster(context, node, parameters.build(), comms, metrics, authentication);
        }).collect(Collectors.toList());

        System.out.println("Messaging with " + messengers.size() + " members");
        var scheduler = Executors.newScheduledThreadPool(3);
        messengers.forEach(view -> view.start(Duration.ofMillis(10), scheduler));

        Map<Member, Receiver> receivers = new HashMap<>();
        AtomicInteger current = new AtomicInteger(-1);
        for (var view : messengers) {
            Receiver receiver = new Receiver(view.getMember().getId(), messengers.size(), current);
            view.registerHandler(receiver);
            receivers.put(view.getMember(), receiver);
        }
        int rounds = Boolean.getBoolean("large_tests") ? 100 : 10;
        for (int r = 0; r < rounds; r++) {
            CountDownLatch round = new CountDownLatch(messengers.size());
            for (Receiver receiver : receivers.values()) {
                receiver.setRound(round);
            }
            var rnd = r;
            messengers.stream().forEach(view -> {
                byte[] rand = new byte[32];
                Entropy.nextSecureBytes(rand);
                ByteBuffer buf = ByteBuffer.wrap(new byte[36]);
                buf.putInt(rnd);
                buf.put(rand);
                buf.flip();
                view.publish(ByteMessage.newBuilder().setContents(ByteString.copyFrom(buf)).build(), true);
            });
            boolean success = round.await(10, TimeUnit.SECONDS);
            assertTrue(success, "Did not complete round: " + r + " waiting for: " + round.getCount());

            current.incrementAndGet();
            for (Receiver receiver : receivers.values()) {
                receiver.reset();
            }
        }
        communications.forEach(e -> e.close(Duration.ofMillis(1)));

        System.out.println();

        ConsoleReporter.forRegistry(registry)
                       .convertRatesTo(TimeUnit.SECONDS)
                       .convertDurationsTo(TimeUnit.MILLISECONDS)
                       .build()
                       .report();
    }

    class Receiver implements ReliableBroadcaster.MessageHandler {
        final Set<Digest>                     counted = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final AtomicInteger                   current;
        final Digest                          memberId;
        final AtomicReference<CountDownLatch> round   = new AtomicReference<>();

        Receiver(Digest memberId, int cardinality, AtomicInteger current) {
            this.current = current;
            this.memberId = memberId;
        }

        @Override
        public void message(Digest context, List<ReliableBroadcaster.Msg> messages) {
            messages.forEach(m -> {
                assert m.source() != null : "null member";
                ByteBuffer buf;
                try {
                    buf = m.content().unpack(ByteMessage.class).getContents().asReadOnlyByteBuffer();
                } catch (InvalidProtocolBufferException e) {
                    throw new IllegalStateException(e);
                }
                assert buf.remaining() > 4 : "buffer: " + buf.remaining();
                final var index = buf.getInt();
                System.out.println("received: %s from: %s".formatted(index, m.source()));
                if (index == current.get() + 1) {
                    if (counted.add(m.source().get(0))) {
                        int totalCount = totalReceived.incrementAndGet();
                        if (totalCount % 1_000 == 0) {
                            System.out.print(".");
                        }
                        if (totalCount % 80_000 == 0) {
                            System.out.println();
                        }
                        if (counted.size() == messengers.size() - 1) {
                            round.get().countDown();
                        }
                    }
                }
            });
        }

        public void setRound(CountDownLatch round) {
            this.round.set(round);
        }

        void reset() {
            counted.clear();
        }
    }
}
