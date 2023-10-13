package com.hellblazer.rbc.sync.comms;

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
