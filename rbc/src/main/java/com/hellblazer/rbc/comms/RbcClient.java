/*
 * Copyright (c) 2023. Hal Hildebrand, All Rights Reserved.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package com.hellblazer.rbc.comms;

import com.codahale.metrics.Timer.Context;
import com.hellblazer.archipelago.ManagedServerChannel;
import com.hellblazer.archipelago.ServerConnectionCache;
import com.hellblazer.archipelago.membership.Member;
import com.hellblazer.messaging.proto.MessageBff;
import com.hellblazer.messaging.proto.RBCGrpc;
import com.hellblazer.messaging.proto.Reconcile;
import com.hellblazer.messaging.proto.ReconcileContext;
import com.hellblazer.rbc.RbcMetrics;

/**
 * @author hal.hildebrand
 * @since 220
 */
public class RbcClient implements ReliableBroadcast {

    private final ManagedServerChannel    channel;
    private final RBCGrpc.RBCBlockingStub client;
    private final RbcMetrics              metrics;

    public RbcClient(ManagedServerChannel c, RbcMetrics metrics) {
        this.channel = c;
        this.client = RBCGrpc.newBlockingStub(c).withCompression("gzip");
        this.metrics = metrics;
    }

    public static ServerConnectionCache.CreateClientCommunications<ReliableBroadcast> getCreate(RbcMetrics metrics) {
        return (c) -> {
            return new RbcClient(c, metrics);
        };

    }

    @Override
    public void close() {
        channel.release();
    }

    @Override
    public Member getMember() {
        return channel.getMember();
    }

    @Override
    public Reconcile gossip(MessageBff request) {
        Context timer = metrics == null ? null : metrics.outboundGossipTimer().time();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.outboundBandwidth().mark(serializedSize);
            metrics.outboundGossip().update(serializedSize);
        }
        var result = client.gossip(request);
        if (metrics != null) {
            timer.stop();
            var serializedSize = result.getSerializedSize();
            metrics.inboundBandwidth().mark(serializedSize);
            metrics.gossipResponse().update(serializedSize);
        }
        return result;
    }

    public void start() {

    }

    @Override
    public String toString() {
        return String.format("->[%s]", channel.getMember());
    }

    @Override
    public void update(ReconcileContext request) {
        Context timer = metrics == null ? null : metrics.outboundUpdateTimer().time();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.outboundBandwidth().mark(serializedSize);
            metrics.outboundUpdate().update(serializedSize);
        }
        try {
            var result = client.update(request);
            if (metrics != null) {
                if (timer != null) {
                    timer.stop();
                }
            }
        } catch (Throwable e) {
            if (timer != null) {
                timer.close();
            }
        }
    }
}
