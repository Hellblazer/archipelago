/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.archipelago;

import com.hellblazer.archipelago.membership.Member;

import java.io.Closeable;

/**
 * A client side link
 *
 * @author hal.hildebrand
 */
public interface Link extends Closeable {

    Member getMember();
}
