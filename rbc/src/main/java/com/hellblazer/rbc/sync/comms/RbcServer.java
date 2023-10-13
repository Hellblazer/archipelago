package com.hellblazer.rbc.sync.comms;

import com.codahale.metrics.Timer.Context;
import com.google.protobuf.Empty;
import com.hellblazer.archipelago.RoutableService;
import com.hellblazer.archipelago.protocols.ClientIdentity;
import com.hellblazer.cryptography.hash.Digest;
import com.hellblazer.messaging.proto.MessageBff;
import com.hellblazer.messaging.proto.RBCGrpc;
import com.hellblazer.messaging.proto.Reconcile;
import com.hellblazer.messaging.proto.ReconcileContext;
import com.hellblazer.rbc.RbcMetrics;
import com.hellblazer.rbc.sync.ReliableBroadcaster;
import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 *
 */
public class RbcServer extends RBCGrpc.RBCImplBase {
    private       ClientIdentity identity;
    private final RbcMetrics                                   metrics;
    private final RoutableService<ReliableBroadcaster.Service> routing;

    public RbcServer(ClientIdentity identity, RbcMetrics metrics, RoutableService<ReliableBroadcaster.Service> r) {
        this.metrics = metrics;
        this.identity = identity;
        this.routing = r;
    }

    public ClientIdentity getClientIdentity() {
        return identity;
    }

    @Override
    public void gossip(MessageBff request, StreamObserver<Reconcile> responseObserver) {
        Context timer = metrics == null ? null : metrics.inboundGossipTimer().time();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.inboundBandwidth().mark(serializedSize);
            metrics.inboundGossip().update(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        routing.evaluate(responseObserver, s -> {
            try {
                Reconcile response = s.gossip(request, from);
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                if (metrics != null) {
                    var serializedSize = response.getSerializedSize();
                    metrics.outboundBandwidth().mark(serializedSize);
                    metrics.gossipReply().update(serializedSize);
                }
            } finally {
                if (timer != null) {
                    timer.stop();
                }
            }
        });
    }

    @Override
    public void update(ReconcileContext request, StreamObserver<Empty> responseObserver) {
        Context timer = metrics == null ? null : metrics.inboundUpdateTimer().time();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.inboundBandwidth().mark(serializedSize);
            metrics.inboundUpdate().update(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        routing.evaluate(responseObserver, s -> {
            try {
                s.update(request, from);
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } finally {
                if (timer != null) {
                    timer.stop();
                }
            }
        });
    }

}
