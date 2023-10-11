/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.comm.grpc;

import com.google.common.primitives.Ints;
import com.google.protobuf.Any;
import com.hellblazer.test.proto.PeerCreds;
import com.hellblazer.test.proto.TestItGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.DomainSocketNegotiatorHandler.DomainSocketNegotiator;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.Channel;
import io.netty.channel.unix.DomainSocketAddress;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.hellblazer.comm.grpc.DomainSocketServerInterceptor.PEER_CREDENTIALS_CONTEXT_KEY;
import static com.hellblazer.comm.grpc.DomainSockets.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author hal.hildebrand
 */
public class DomainSocketTest {

    private static final Class<? extends Channel> channelType = getChannelType();

    @Test
    public void smokin() throws Exception {
        Path socketPath = Path.of("target").resolve("smokin.socket");
        Files.deleteIfExists(socketPath);
        assertFalse(Files.exists(socketPath));

        final var eventLoopGroup = getEventLoopGroup();
        var server = NettyServerBuilder.forAddress(new DomainSocketAddress(socketPath.toFile()))
                                       .protocolNegotiator(new DomainSocketNegotiator())
                                       .channelType(getServerDomainSocketChannelClass())
                                       .workerEventLoopGroup(eventLoopGroup)
                                       .bossEventLoopGroup(eventLoopGroup)
                                       .addService(new TestServer())
                                       .intercept(new DomainSocketServerInterceptor())
                                       .build();
        server.start();
        assertTrue(Files.exists(socketPath));

        ManagedChannel channel = NettyChannelBuilder.forAddress(new DomainSocketAddress(socketPath.toFile()))
                                                    .eventLoopGroup(eventLoopGroup)
                                                    .channelType(channelType)
                                                    .keepAliveTime(1, TimeUnit.MILLISECONDS)
                                                    .usePlaintext()
                                                    .build();
        try {
            var stub = TestItGrpc.newBlockingStub(channel);

            var result = stub.ping(Any.getDefaultInstance());
            assertNotNull(result);
            var creds = result.unpack(PeerCreds.class);
            assertNotNull(creds);

            System.out.println("Success:\n" + creds);
        } finally {
            channel.shutdown();
        }
    }

    public static class TestServer extends TestItGrpc.TestItImplBase {

        @Override
        public void ping(Any request, StreamObserver<Any> responseObserver) {
            final var credentials = PEER_CREDENTIALS_CONTEXT_KEY.get();
            if (credentials == null) {
                responseObserver.onError(
                new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("No credentials available")));
                return;
            }
            responseObserver.onNext(Any.pack(PeerCreds.newBuilder()
                                                      .setPid(credentials.pid())
                                                      .setUid(credentials.uid())
                                                      .addAllGids(Ints.asList(credentials.gids()))
                                                      .build()));
            responseObserver.onCompleted();
        }

    }

}
