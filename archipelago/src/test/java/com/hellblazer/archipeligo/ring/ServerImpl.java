package com.hellblazer.archipeligo.ring;

import com.google.protobuf.Any;
import com.hellblazer.archipelago.RoutableService;
import com.hellblazer.test.proto.TestItGrpc;
import io.grpc.stub.StreamObserver;

public class ServerImpl extends TestItGrpc.TestItImplBase {
    private final RoutableService<TestIt> router;

    public ServerImpl(RoutableService<TestIt> router) {
        this.router = router;
    }

    @Override
    public void ping(Any request, StreamObserver<Any> responseObserver) {
        router.evaluate(responseObserver, t -> t.ping(request, responseObserver));
    }
}
