/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.archipelago;

import com.hellblazer.archipelago.membership.Member;
import com.hellblazer.cryptography.ssl.CertificateValidator;
import io.netty.handler.ssl.ClientAuth;

import java.net.SocketAddress;

/**
 * @author hal.hildebrand
 */
public interface EndpointProvider {

    SocketAddress addressFor(Member to);

    String getAlias();

    SocketAddress getBindAddress();

    ClientAuth getClientAuth();

    CertificateValidator getValiator();

}
