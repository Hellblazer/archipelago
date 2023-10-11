/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.archipelago.protocols;

import com.hellblazer.cryptography.hash.Digest;

/**
 * @author hal.hildebrand
 */
public interface ClientIdentity {

    Digest getFrom();

}
