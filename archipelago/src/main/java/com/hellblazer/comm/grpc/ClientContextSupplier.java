/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.comm.grpc;

import com.hellblazer.cryptography.ssl.CertificateValidator;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;

/**
 * @author hal.hildebrand
 */
public interface ClientContextSupplier {

    SslContext forClient(ClientAuth clientAuth, String alias, CertificateValidator validator, String tlsVersion);
}
