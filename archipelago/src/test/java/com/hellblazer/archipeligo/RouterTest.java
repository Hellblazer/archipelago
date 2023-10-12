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
import com.hellblazer.cryptography.hash.DigestAlgorithm;
import com.hellblazer.test.proto.ByteMessage;
import com.hellblazer.test.proto.TestItGrpc;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author hal.hildebrand
 */
public class RouterTest {
    @Test
    public void smokin() throws Exception {
        var local = new TestItService() {

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
        var serverMember = new SigningMemberImpl(Utils.getMember(0));
        final var name = UUID.randomUUID().toString();

        var serverBuilder = InProcessServerBuilder.forName(name);
        var cacheBuilder = ServerConnectionCache.newBuilder()
                                                .setFactory(to -> InProcessChannelBuilder.forName(name).build());
        var router = new RouterImpl(serverMember, serverBuilder, cacheBuilder, null);
        final var ctxA = DigestAlgorithm.DEFAULT.getOrigin().prefix(0x666);
        RouterImpl.CommonCommunications<TestItService, TestIt> commsA = router.create(serverMember, ctxA, new ServerA(),
                                                                                      "A", r -> new Server(r),
                                                                                      c -> new TestItClient(c), local);

        final var ctxB = DigestAlgorithm.DEFAULT.getLast().prefix(0x666);
        RouterImpl.CommonCommunications<TestItService, TestIt> commsB = router.create(serverMember, ctxB, new ServerB(),
                                                                                      "A", r -> new Server(r),
                                                                                      c -> new TestItClient(c), local);

        router.start();

        var clientA = commsA.connect(new SigningMemberImpl(Utils.getMember(1)));

        var resultA = clientA.ping(Any.getDefaultInstance());
        assertNotNull(resultA);
        var msg = resultA.unpack(ByteMessage.class);
        assertEquals("Hello Server A", msg.getContents().toStringUtf8());

        var clientB = commsB.connect(new SigningMemberImpl(Utils.getMember(2)));
        var resultB = clientB.ping(Any.getDefaultInstance());
        assertNotNull(resultB);
        msg = resultB.unpack(ByteMessage.class);
        assertEquals("Hello Server B", msg.getContents().toStringUtf8());

        router.close(Duration.ofSeconds(1));
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
