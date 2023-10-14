package com.hellblazer.archipeligo.ring;

import com.google.protobuf.Any;
import com.hellblazer.archipelago.RouterImpl;
import com.hellblazer.archipelago.ServerConnectionCache;
import com.hellblazer.archipelago.Utils;
import com.hellblazer.archipelago.membership.Context;
import com.hellblazer.archipelago.membership.Member;
import com.hellblazer.archipelago.membership.impl.SigningMemberImpl;
import com.hellblazer.archipelago.ring.SyncRingIterator;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 **/
public class SyncRingIteratorTest {
    @Test
    public void smokin() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0));
        var serverMember2 = new SigningMemberImpl(Utils.getMember(1));
        var pinged1 = new AtomicBoolean();
        var pinged2 = new AtomicBoolean();

        var local1 = new TestItService() {

            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember1;
            }

            @Override
            public Any ping(Any request) {
                pinged1.set(true);
                return Any.getDefaultInstance();
            }
        };
        var local2 = new TestItService() {

            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember2;
            }

            @Override
            public Any ping(Any request) {
                pinged2.set(true);
                return Any.getDefaultInstance();
            }
        };
        final var name = UUID.randomUUID().toString();
        Context<Member> context = Context.newBuilder().build();
        context.activate(serverMember1);
        context.activate(serverMember2);

        var serverBuilder = InProcessServerBuilder.forName(name);
        var cacheBuilder = ServerConnectionCache.newBuilder().setFactory(to -> InProcessChannelBuilder.forName(name).build());
        var router = new RouterImpl(serverMember1, serverBuilder, cacheBuilder, null);
        var commsA = router.create(serverMember1, context.getId(), new ServiceImpl(local1, "A"), "A", ServerImpl::new, TestItClient::new, local1);

        var commsB = router.create(serverMember2, context.getId(), new ServiceImpl(local2, "B"), "B", ServerImpl::new, TestItClient::new, local2);

        try {
            router.start();
            var frequency = Duration.ofMillis(1);
            var scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
            var sync = new SyncRingIterator<Member, TestItService>(frequency, context, serverMember1, scheduler, commsA);
            var countdown = new CountDownLatch(3);
            sync.iterate(context.getId(), (link, round) -> link.ping(Any.getDefaultInstance()), (round, result, link) -> {
                countdown.countDown();
                return true;
            });
            assertTrue(countdown.await(1, TimeUnit.SECONDS));
            assertFalse(pinged1.get());
            assertTrue(pinged2.get());
        } finally {
            router.close(Duration.ofSeconds(1));
        }
    }
}
