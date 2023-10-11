/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.archipelago;

import com.google.common.io.ByteStreams;
import io.grpc.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Proxy from one GRPC server to another GRPC server
 *
 * @author hal.hildebrand
 */
abstract public class GrpcProxy implements ServerCallHandler<byte[], byte[]> {
    public HandlerRegistry newRegistry() {
        return new HandlerRegistry() {
            private final MethodDescriptor.Marshaller<byte[]> byteMarshaller = new ByteMarshaller();

            @Override
            public ServerMethodDefinition<byte[], byte[]> lookupMethod(String methodName, String authority) {
                MethodDescriptor<byte[], byte[]> methodDescriptor = MethodDescriptor.newBuilder(byteMarshaller,
                                                                                                byteMarshaller)
                                                                                    .setFullMethodName(methodName)
                                                                                    .setType(
                                                                                    MethodDescriptor.MethodType.UNKNOWN)
                                                                                    .build();
                return ServerMethodDefinition.create(methodDescriptor, GrpcProxy.this);
            }
        };
    }

    @Override
    public ServerCall.Listener<byte[]> startCall(ServerCall<byte[], byte[]> serverCall, Metadata headers) {
        final var channel = getChannel();
        try {
            var clientCall = channel.newCall(serverCall.getMethodDescriptor(), CallOptions.DEFAULT);
            var proxy = new CallProxy<>(serverCall, clientCall);
            clientCall.start(proxy.clientCallListener, headers);
            serverCall.request(1);
            clientCall.request(1);
            return proxy.serverCallListener;
        } finally {
            channel.shutdown();
        }
    }

    protected abstract ManagedChannel getChannel();

    private static class ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        @Override
        public byte[] parse(InputStream stream) {
            try {
                return ByteStreams.toByteArray(stream);
            } catch (IOException ex) {
                throw new RuntimeException();
            }
        }

        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }
    }

    private static class CallProxy<ReqT, RespT> {
        private final ResponseProxy clientCallListener;
        private final RequestProxy  serverCallListener;

        public CallProxy(ServerCall<ReqT, RespT> serverCall, ClientCall<ReqT, RespT> clientCall) {
            serverCallListener = new RequestProxy(clientCall);
            clientCallListener = new ResponseProxy(serverCall);
        }

        private class RequestProxy extends ServerCall.Listener<ReqT> {
            private final ClientCall<ReqT, ?> clientCall;
            private final Lock                lock = new ReentrantLock();
            private       boolean             needToRequest;

            public RequestProxy(ClientCall<ReqT, ?> clientCall) {
                this.clientCall = clientCall;
            }

            @Override
            public void onCancel() {
                clientCall.cancel("Server cancelled", null);
            }

            @Override
            public void onHalfClose() {
                clientCall.halfClose();
            }

            @Override
            public void onMessage(ReqT message) {
                clientCall.sendMessage(message);
                lock.lock();
                try {
                    if (clientCall.isReady()) {
                        clientCallListener.serverCall.request(1);
                    } else {
                        needToRequest = true;
                    }
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public void onReady() {
                clientCallListener.onServerReady();
            }

            void onClientReady() {
                lock.lock();
                try {
                    if (needToRequest) {
                        clientCallListener.serverCall.request(1);
                        needToRequest = false;
                    }
                } finally {
                    lock.unlock();
                }
            }
        }

        private class ResponseProxy extends ClientCall.Listener<RespT> {
            private final Lock                 lock = new ReentrantLock();
            private final ServerCall<?, RespT> serverCall;
            private       boolean              needToRequest;

            public ResponseProxy(ServerCall<?, RespT> serverCall) {
                this.serverCall = serverCall;
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                serverCall.close(status, trailers);
            }

            @Override
            public void onHeaders(Metadata headers) {
                serverCall.sendHeaders(headers);
            }

            @Override
            public void onMessage(RespT message) {
                serverCall.sendMessage(message);
                lock.lock();
                try {
                    if (serverCall.isReady()) {
                        serverCallListener.clientCall.request(1);
                    } else {
                        needToRequest = true;
                    }
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public void onReady() {
                serverCallListener.onClientReady();
            }

            void onServerReady() {
                lock.lock();
                try {
                    if (needToRequest) {
                        serverCallListener.clientCall.request(1);
                        needToRequest = false;
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
    }

}
