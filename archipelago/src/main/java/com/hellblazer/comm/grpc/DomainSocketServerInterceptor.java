/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.comm.grpc;

import io.grpc.*;
import io.netty.channel.unix.PeerCredentials;

import static io.grpc.netty.DomainSocketNegotiatorHandler.TRANSPORT_ATTR_PEER_CREDENTIALS;

/**
 * @author hal.hildebrand
 */
public class DomainSocketServerInterceptor implements ServerInterceptor {

    public static final Context.Key<PeerCredentials> PEER_CREDENTIALS_CONTEXT_KEY = Context.key(
    "com.salesforce.apollo.PEER_CREDENTIALS");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                 final Metadata requestHeaders,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        var principal = call.getAttributes().get(TRANSPORT_ATTR_PEER_CREDENTIALS);
        if (principal == null) {
            call.close(Status.INTERNAL.withCause(new NullPointerException("Principal is missing"))
                                      .withDescription("Principal is missing"), null);
            return new ServerCall.Listener<ReqT>() {
            };
        }
        Context ctx = Context.current().withValue(PEER_CREDENTIALS_CONTEXT_KEY, principal);
        return Contexts.interceptCall(ctx, call, requestHeaders, next);
    }

}
