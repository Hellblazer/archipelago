/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.archipelago;

import com.hellblazer.archipelago.membership.Member;
import com.hellblazer.archipelago.protocols.EndpointProvider;
import com.hellblazer.cryptography.ssl.CertificateValidator;
import io.netty.handler.ssl.ClientAuth;

import java.net.SocketAddress;
import java.util.function.Function;

/**
 * @author hal.hildebrand
 */
public class StandardEpProvider implements EndpointProvider {

    private final SocketAddress                   bindAddress;
    private final ClientAuth                      clientAuth;
    private final Function<Member, SocketAddress> resolver;
    private final CertificateValidator            validator;

    public StandardEpProvider(SocketAddress bindAddress, ClientAuth clientAuth, CertificateValidator validator,
                              Function<Member, SocketAddress> resolver) {
        this.bindAddress = bindAddress;
        this.clientAuth = clientAuth;
        this.validator = validator;
        this.resolver = resolver;
    }

    @Override
    public SocketAddress addressFor(Member to) {
        return resolver.apply(to);
    }

    @Override
    public String getAlias() {
        return "node";
    }

    @Override
    public SocketAddress getBindAddress() {
        return bindAddress;
    }

    @Override
    public ClientAuth getClientAuth() {
        return clientAuth;
    }

    @Override
    public CertificateValidator getValiator() {
        return validator;
    }

}
