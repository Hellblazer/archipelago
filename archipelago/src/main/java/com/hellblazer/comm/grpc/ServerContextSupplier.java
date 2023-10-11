/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.comm.grpc;

import com.hellblazer.cryptography.hash.Digest;
import com.hellblazer.cryptography.ssl.CertificateValidator;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;

import java.security.Provider;
import java.security.cert.X509Certificate;

/**
 * @author hal.hildebrand
 */
public interface ServerContextSupplier {

    SslContext forServer(ClientAuth clientAuth, String alias, CertificateValidator validator, Provider provider);

    Digest getMemberId(X509Certificate key);

}
