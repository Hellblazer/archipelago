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
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author hal.hildebrand
 */
public class LocalServerTest {
    private final TestItService local = new TestItService() {

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

    @Test
    public void smokin() throws Exception {
        final var memberA = new SigningMemberImpl(Utils.getMember(0));
        final var memberB = new SigningMemberImpl(Utils.getMember(1));
        final var ctxA = DigestAlgorithm.DEFAULT.getOrigin().prefix(0x666);
        final var prefix = UUID.randomUUID().toString();
        final var exec = Executors.newVirtualThreadPerTaskExecutor();

        RouterSupplier serverA = new LocalServer(prefix, memberA, exec);
        var routerA = serverA.router(ServerConnectionCache.newBuilder(), exec);

        RouterImpl.CommonCommunications<TestItService, TestIt> commsA = routerA.create(memberA, ctxA, new ServerA(),
                                                                                       "A", r -> new Server(r),
                                                                                       c -> new TestItClient(c), local);

        RouterSupplier serverB = new LocalServer(prefix, memberB, exec);
        var routerB = serverB.router(ServerConnectionCache.newBuilder(), exec);

        RouterImpl.CommonCommunications<TestItService, TestIt> commsA_B = routerB.create(memberB, ctxA, new ServerB(),
                                                                                         "B", r -> new Server(r),
                                                                                         c -> new TestItClient(c),
                                                                                         local);

        routerA.start();
        routerB.start();

        var clientA = commsA.connect(memberB);

        var resultA = clientA.ping(Any.getDefaultInstance());
        assertNotNull(resultA);
        assertEquals("Hello Server B", resultA.unpack(ByteMessage.class).getContents().toStringUtf8());

        var clientB = commsA_B.connect(memberA);
        var resultB = clientB.ping(Any.getDefaultInstance());
        assertNotNull(resultB);
        assertEquals("Hello Server A", resultB.unpack(ByteMessage.class).getContents().toStringUtf8());

        routerA.close(Duration.ofSeconds(1));
        routerB.close(Duration.ofSeconds(1));
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
