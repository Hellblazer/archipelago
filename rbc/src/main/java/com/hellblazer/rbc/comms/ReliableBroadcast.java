/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.rbc.comms;

import com.google.common.util.concurrent.ListenableFuture;
import com.hellblazer.archipelago.Link;
import com.hellblazer.archipelago.membership.Member;
import com.hellblazer.archipelago.membership.SigningMember;
import com.hellblazer.messaging.proto.MessageBff;
import com.hellblazer.messaging.proto.Reconcile;
import com.hellblazer.messaging.proto.ReconcileContext;

import java.io.IOException;

/**
 * @author hal.hildebrand
 */
public interface ReliableBroadcast extends Link {

    static ReliableBroadcast getLocalLoopback(SigningMember member) {
        return new ReliableBroadcast() {

            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return member;
            }

            @Override
            public ListenableFuture<Reconcile> gossip(MessageBff bff) {
                return null;
            }

            @Override
            public void update(ReconcileContext push) {
            }
        };
    }

    ListenableFuture<Reconcile> gossip(MessageBff bff);

    void update(ReconcileContext push);

}
