/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.archipeligo;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.hellblazer.archipelago.*;
import com.hellblazer.archipelago.membership.Member;
import com.hellblazer.archipelago.membership.impl.SigningMemberImpl;
import com.hellblazer.comm.grpc.DomainSocketServerInterceptor;
import com.hellblazer.cryptography.hash.DigestAlgorithm;
import com.hellblazer.test.proto.ByteMessage;
import com.hellblazer.test.proto.TestItGrpc;
import io.grpc.ManagedChannel;
import io.grpc.netty.DomainSocketNegotiatorHandler.DomainSocketNegotiator;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import static com.hellblazer.comm.grpc.DomainSockets.*;
import static com.hellblazer.cryptography.QualifiedBase64.qb64;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author hal.hildebrand
 */
public class EnclaveTest {
    private final static Class<? extends io.netty.channel.Channel> channelType = getChannelType();
    private final TestItService  local = new TestItService() {

        @Override
        public void close() throws IOException {
        }

        @Override
        public Member getMember() {
            return null;
        }

        @Override
        public Any ping(Any request) {
            return null;
        }
    };
    private       EventLoopGroup eventLoopGroup;

    @AfterEach
    public void after() throws Exception {
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
            eventLoopGroup.awaitTermination(1, TimeUnit.SECONDS);

        }
    }

    @BeforeEach
    public void before() {
        eventLoopGroup = getEventLoopGroup();
    }

    @Test
    public void smokin() throws Exception {
        final var ctxA = DigestAlgorithm.DEFAULT.getOrigin().prefix(0x666);
        final var ctxB = DigestAlgorithm.DEFAULT.getLast().prefix(0x666);
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0));
        var serverMember2 = new SigningMemberImpl(Utils.getMember(1));
        final var bridge = new DomainSocketAddress(Path.of("target").resolve(UUID.randomUUID().toString()).toFile());

        final var routes = new HashMap<String, DomainSocketAddress>();
        final Function<String, DomainSocketAddress> router = s -> routes.get(s);
        final var exec = Executors.newVirtualThreadPerTaskExecutor();

        final var portalEndpoint = new DomainSocketAddress(
        Path.of("target").resolve(UUID.randomUUID().toString()).toFile());
        final var agent = DigestAlgorithm.DEFAULT.getLast();
        final var portal = new Portal<>(agent, NettyServerBuilder.forAddress(portalEndpoint)
                                                                 .protocolNegotiator(new DomainSocketNegotiator())
                                                                 .channelType(getServerDomainSocketChannelClass())
                                                                 .workerEventLoopGroup(getEventLoopGroup())
                                                                 .bossEventLoopGroup(getEventLoopGroup())
                                                                 .intercept(new DomainSocketServerInterceptor()),
                                        s -> handler(portalEndpoint), bridge, exec, Duration.ofMillis(1), router);

        final var endpoint1 = new DomainSocketAddress(Path.of("target").resolve(UUID.randomUUID().toString()).toFile());
        var enclave1 = new Enclave(serverMember1, endpoint1, exec, bridge, d -> {
            routes.put(qb64(d), endpoint1);
        });
        var router1 = enclave1.router(exec);
        RouterImpl.CommonCommunications<TestItService, TestIt> commsA = router1.create(serverMember1, ctxA, new ServerA(), "A",
                                                                            r -> new Server(r),
                                                                            c -> new TestItClient(c), local);

        final var endpoint2 = new DomainSocketAddress(Path.of("target").resolve(UUID.randomUUID().toString()).toFile());
        var enclave2 = new Enclave(serverMember2, endpoint2, exec, bridge, d -> {
            routes.put(qb64(d), endpoint2);
        });
        var router2 = enclave2.router(exec);
        RouterImpl.CommonCommunications<TestItService, TestIt> commsB = router2.create(serverMember2, ctxB, new ServerB(), "A",
                                                                            r -> new Server(r),
                                                                            c -> new TestItClient(c), local);

        portal.start();
        router1.start();
        router2.start();

        var clientA = commsA.connect(serverMember2);

        var resultA = clientA.ping(Any.getDefaultInstance());
        assertNotNull(resultA);
        var msg = resultA.unpack(ByteMessage.class);
        assertEquals("Hello Server A", msg.getContents().toStringUtf8());

        var clientB = commsB.connect(serverMember1);
        var resultB = clientB.ping(Any.getDefaultInstance());
        assertNotNull(resultB);
        msg = resultB.unpack(ByteMessage.class);
        assertEquals("Hello Server B", msg.getContents().toStringUtf8());

        portal.close(Duration.ofSeconds(1));
        router1.close(Duration.ofSeconds(1));
        router2.close(Duration.ofSeconds(1));
    }

    private ManagedChannel handler(DomainSocketAddress address) {
        return NettyChannelBuilder.forAddress(address)
                                  .eventLoopGroup(eventLoopGroup)
                                  .channelType(channelType)
                                  .keepAliveTime(1, TimeUnit.SECONDS)
                                  .usePlaintext()
                                  .build();
    }

    public static interface TestIt {
        void ping(Any request, StreamObserver<Any> responseObserver);
    }
    public static interface TestItService extends Link {
        Any ping(Any request);
    }

    public static class Server extends TestItGrpc.TestItImplBase {
        private final RoutableService<TestIt> router;

        public Server(RoutableService<TestIt> router) {
            this.router = router;
        }

        @Override
        public void ping(Any request, StreamObserver<Any> responseObserver) {
            router.evaluate(responseObserver, t -> t.ping(request, responseObserver));
        }
    }

    public static class TestItClient implements TestItService {
        private final TestItGrpc.TestItBlockingStub client;
        private final ManagedServerChannel          connection;

        public TestItClient(ManagedServerChannel c) {
            this.connection = c;
            client = TestItGrpc.newBlockingStub(c);
        }

        @Override
        public void close() throws IOException {
            connection.release();
        }

        @Override
        public Member getMember() {
            return connection.getMember();
        }

        @Override
        public Any ping(Any request) {
            return client.ping(request);
        }
    }

    public class ServerA implements TestIt {
        @Override
        public void ping(Any request, StreamObserver<Any> responseObserver) {
            responseObserver.onNext(
            Any.pack(ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("Hello Server A")).build()));
            responseObserver.onCompleted();
        }
    }

    public class ServerB implements TestIt {
        @Override
        public void ping(Any request, StreamObserver<Any> responseObserver) {
            responseObserver.onNext(
            Any.pack(ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("Hello Server B")).build()));
            responseObserver.onCompleted();
        }
    }
}
