/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.archipelago.membership;

import com.hellblazer.cryptography.hash.Digest;

import java.util.List;

/**
 * @author hal.hildebrand
 */
public interface ViewChangeListener {
    void viewChange(Digest viewId, List<Digest> joins, List<Digest> leaves);
}
