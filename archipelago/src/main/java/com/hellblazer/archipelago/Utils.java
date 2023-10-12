package com.hellblazer.archipelago;

import com.hellblazer.cryptography.SignatureAlgorithm;
import com.hellblazer.cryptography.cert.BcX500NameDnImpl;
import com.hellblazer.cryptography.cert.CertificateWithPrivateKey;
import com.hellblazer.cryptography.cert.Certificates;
import com.hellblazer.cryptography.hash.Digest;
import com.hellblazer.cryptography.hash.DigestAlgorithm;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static com.hellblazer.cryptography.QualifiedBase64.qb64;

/**
 * @author hal.hildebrand
 **/
public class Utils {

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
        X509Certificate generated = Certificates.selfSign(false,
                                                          encode(id, localhost, allocatePort(), keyPair.getPublic()),
                                                          keyPair, notBefore, notAfter, Collections.emptyList());
        return new CertificateWithPrivateKey(generated, keyPair.getPrivate());
    }

    public static CertificateWithPrivateKey getMember(int index) {
        byte[] hash = new byte[32];
        hash[0] = (byte) index;
        return getMember(new Digest(DigestAlgorithm.DEFAULT, hash));
    }

    public static <T> Callable<T> wrapped(Callable<T> c, Logger log) {
        return () -> {
            try {
                return c.call();
            } catch (Exception e) {
                log.error("Error in call", e);
                throw new IllegalStateException(e);
            }
        };
    }

    /**
     * Find a free port for any local address
     *
     * @return the port number or -1 if none available
     */
    public static int allocatePort() {
        return allocatePort(null);
    }

    public static BcX500NameDnImpl encode(Digest digest, String host, int port, PublicKey signingKey) {
        return new BcX500NameDnImpl(
        String.format("CN=%s, L=%s, UID=%s, DC=%s", host, port, qb64(digest), qb64(signingKey)));
    }

    /**
     * Find a free port on the interface with the given address
     *
     * @return the port number or -1 if none available
     */
    public static int allocatePort(InetAddress host) {
        InetAddress address = null;
        try {
            address = host == null ? InetAddress.getLocalHost() : host;
        } catch (UnknownHostException e1) {
            return -1;
        }

        try (ServerSocket socket = new ServerSocket(0, 0, address);) {
            socket.setReuseAddress(true);
            var localPort = socket.getLocalPort();
            socket.close();
            return localPort;
        } catch (IOException e) {
        }
        return -1;
    }

    public static <T> Consumer<T> wrapped(Consumer<T> c, Logger log) {
        return t -> {
            try {
                c.accept(t);
            } catch (Exception e) {
                log.error("Error in call", e);
                throw new IllegalStateException(e);
            }
        };
    }

    public static Runnable wrapped(Runnable r, Logger log) {
        return () -> {
            try {
                r.run();
            } catch (Throwable e) {
                log.error("Error in execution", e);
                throw new IllegalStateException(e);
            }
        };
    }
}
