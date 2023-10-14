/*
 * Copyright (c) 2023. Hal Hildebrand, All Rights Reserved.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package com.hellblazer.rbc.comms;

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
            public Reconcile gossip(MessageBff bff) {
                return null;
            }

            @Override
            public void update(ReconcileContext push) {
            }
        };
    }

    Reconcile gossip(MessageBff bff);

    void update(ReconcileContext push);

}
