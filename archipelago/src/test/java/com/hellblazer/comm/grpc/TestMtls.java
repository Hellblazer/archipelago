/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.comm.grpc;

import com.google.protobuf.Any;
import com.hellblazer.archipelago.Utils;
import com.hellblazer.cryptography.SignatureAlgorithm;
import com.hellblazer.cryptography.cert.BcX500NameDnImpl;
import com.hellblazer.cryptography.cert.CertificateWithPrivateKey;
import com.hellblazer.cryptography.cert.Certificates;
import com.hellblazer.cryptography.hash.Digest;
import com.hellblazer.cryptography.hash.DigestAlgorithm;
import com.hellblazer.cryptography.ssl.CertificateValidator;
import com.hellblazer.test.proto.TestItGrpc;
import io.grpc.stub.StreamObserver;
import io.grpc.util.MutableHandlerRegistry;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.KeyPair;
import java.security.Provider;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.ForkJoinPool;

import static com.hellblazer.cryptography.QualifiedBase64.qb64;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author hal.hildebrand
 */
public class TestMtls {

    public static CertificateWithPrivateKey getMember(Digest id) {
        KeyPair keyPair = SignatureAlgorithm.ED_25519.generateKeyPair();
        var notBefore = Instant.now();
        var notAfter = Instant.now().plusSeconds(10_000);
        String localhost;
        try {
            localhost = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Cannot resolve local host name", e);
        }
        X509Certificate generated = Certificates.selfSign(false, Utils.encode(id, localhost, Utils.allocatePort(),
                                                                              keyPair.getPublic()), keyPair, notBefore,
                                                          notAfter, Collections.emptyList());
        return new CertificateWithPrivateKey(generated, keyPair.getPrivate());
    }
    public static CertificateWithPrivateKey getMember(int index) {
        byte[] hash = new byte[32];
        hash[0] = (byte) index;
        return getMember(new Digest(DigestAlgorithm.DEFAULT, hash));
    }

    @Test
    public void smoke() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress(InetAddress.getLocalHost(), Utils.allocatePort());

        MtlsServer server = server(serverAddress);
        server.start();
        server.bind(server());
        MtlsClient client = client(serverAddress);
        try {

            for (int i = 0; i < 100; i++) {
                Any tst = TestItGrpc.newBlockingStub(client.getChannel()).ping(Any.getDefaultInstance());

                assertNotNull(tst);
            }
        } finally {
            client.stop();
            server.stop();
        }
    }

    private MtlsClient client(InetSocketAddress serverAddress) {
        CertificateWithPrivateKey clientCert = clientIdentity();

        MtlsClient client = new MtlsClient(serverAddress, ClientAuth.REQUIRE, "foo", clientCert.getX509Certificate(),
                                           clientCert.getPrivateKey(), validator(), r -> r.run());
        return client;
    }

    private CertificateWithPrivateKey clientIdentity() {
        return getMember(0);
    }

    private TestItGrpc.TestItImplBase server() {
        return new TestItGrpc.TestItImplBase() {

            @Override
            public void ping(Any request, StreamObserver<Any> responseObserver) {
                responseObserver.onNext(Any.newBuilder().build());
                responseObserver.onCompleted();
            }
        };
    }

    private MtlsServer server(InetSocketAddress serverAddress) {
        CertificateWithPrivateKey serverCert = serverIdentity();

        MtlsServer server = new MtlsServer(serverAddress, ClientAuth.REQUIRE, "foo", new ServerContextSupplier() {

            @Override
            public SslContext forServer(ClientAuth clientAuth, String alias, CertificateValidator validator,
                                        Provider provider) {
                return MtlsServer.forServer(clientAuth, alias, serverCert.getX509Certificate(),
                                            serverCert.getPrivateKey(), validator);
            }

            @Override
            public Digest getMemberId(X509Certificate key) {
                return Digest.NONE;
            }
        }, validator(), new MutableHandlerRegistry(), ForkJoinPool.commonPool());
        return server;
    }

    private CertificateWithPrivateKey serverIdentity() {
        return getMember(1);
    }

    private CertificateValidator validator() {
        return new CertificateValidator() {
            @Override
            public void validateClient(X509Certificate[] chain) throws CertificateException {
            }

            @Override
            public void validateServer(X509Certificate[] chain) throws CertificateException {
            }
        };
    }
}
