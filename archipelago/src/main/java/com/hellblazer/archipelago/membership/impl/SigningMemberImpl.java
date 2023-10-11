/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.archipelago.membership.impl;

import com.hellblazer.archipelago.membership.Member;
import com.hellblazer.archipelago.membership.SigningMember;
import com.hellblazer.cryptography.JohnHancock;
import com.hellblazer.cryptography.SignatureAlgorithm;
import com.hellblazer.cryptography.Signer;
import com.hellblazer.cryptography.cert.CertificateWithPrivateKey;
import com.hellblazer.cryptography.hash.Digest;

import java.io.InputStream;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;


/**
 * A signiner member of a view. This is a local member to the process that can
 * sign and assert things.
 * 
 * @author hal.hildebrand
 *
 */
public class SigningMemberImpl extends MemberImpl implements SigningMember {

    private final Signer signer;

    /**
     * @param cert
     */
    public SigningMemberImpl(CertificateWithPrivateKey cert) {
        this(Member.getMemberIdentifier(cert.getX509Certificate()), cert.getX509Certificate(), cert.getPrivateKey(),
             new SignerImpl(cert.getPrivateKey()), cert.getX509Certificate().getPublicKey());
    }

    public SigningMemberImpl(Digest id, X509Certificate cert, PrivateKey certKey, Signer signer, PublicKey signerKey) {
        super(id, cert, signerKey);
        this.signer = signer;
    }

    @Override
    public SignatureAlgorithm algorithm() {
        return signer.algorithm();
    }

    @Override
    public JohnHancock sign(InputStream message) {
        return signer.sign(message);
    }
}
