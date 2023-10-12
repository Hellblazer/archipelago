/*
 * Copyright (c) 2019, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.archipelago.membership.impl;

import com.hellblazer.archipelago.membership.Member;
import com.hellblazer.cryptography.JohnHancock;
import com.hellblazer.cryptography.SignatureAlgorithm;
import com.hellblazer.cryptography.hash.Digest;

import java.io.InputStream;
import java.security.PublicKey;
import java.security.cert.X509Certificate;

/**
 * A member of the view
 *
 * @author hal.hildebrand
 * @since 220
 */
public class MemberImpl implements Member {

    /**
     * Signing identity
     */
    protected final X509Certificate    certificate;
    /**
     * Unique ID of the memmber
     */
    protected final Digest             id;
    /**
     * cached signature algorithm for signing key
     */
    protected final SignatureAlgorithm signatureAlgorithm;
    /**
     * Key used by member to sign things
     */
    protected final PublicKey          signingKey;

    public MemberImpl(Digest id, X509Certificate c, PublicKey sk) {
        certificate = c;
        this.id = id;
        this.signingKey = sk;
        signatureAlgorithm = SignatureAlgorithm.lookup(signingKey);
    }

    public MemberImpl(X509Certificate cert) {
        this(Member.getMemberIdentifier(cert), cert, Member.getSigningKey(cert));
    }

    @Override
    public int compareTo(Member o) {
        return id.compareTo(o.getId());
    }

    @Override
    // The id of a member uniquely identifies it
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if ((obj == null) || !(obj instanceof Member))
            return false;
        return id.equals(((Member) obj).getId());
    }

    /**
     * @return the unique id of this member
     */
    @Override
    public Digest getId() {
        return id;
    }

    public PublicKey getPublicKey() {
        return signingKey;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Member" + id;
    }

    /**
     * Verify the signature with the member's signing key
     */
    @Override
    public boolean verify(JohnHancock signature, InputStream message) {
        return new DefaultVerifier(new PublicKey[] { signingKey }).verify(signature, message);
    }
}
