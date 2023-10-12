/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.comm.grpc;

import com.hellblazer.archipelago.protocols.ClientIdentity;
import com.hellblazer.cryptography.hash.Digest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.function.Consumer;

import static com.hellblazer.cryptography.QualifiedBase64.digest;

/**
 * @author hal.hildebrand
 */
public interface BaseServerCommunications<T> {
    default void evaluate(StreamObserver<?> responseObserver, Digest id, Consumer<T> c, T s, Map<Digest, T> services) {
        T service = getService(id, s, services);
        if (service == null) {
            responseObserver.onError(new StatusRuntimeException(Status.UNKNOWN));
        } else {
            c.accept(service);
        }
    }

    default void evaluate(StreamObserver<?> responseObserver, String id, Consumer<T> c, T s, Map<Digest, T> services) {
        evaluate(responseObserver, digest(id), c, s, services);
    }

    ClientIdentity getClientIdentity();

    default Digest getFrom() {
        return getClientIdentity().getFrom();
    }

    default T getService(Digest context, T system, Map<Digest, T> services) {
        return (context == null && system != null) ? system : services.get(context);
    }

    void register(Digest id, T service);

}
