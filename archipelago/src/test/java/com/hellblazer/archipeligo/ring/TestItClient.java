package com.hellblazer.archipeligo.ring;

import com.google.protobuf.Any;
import com.hellblazer.archipelago.ManagedServerChannel;
import com.hellblazer.archipelago.membership.Member;
import com.hellblazer.test.proto.TestItGrpc;

import java.io.IOException;

/**
 * @author hal.hildebrand
 **/
public class TestItClient  implements TestItService {
    private final TestItGrpc.TestItBlockingStub client;
    private final ManagedServerChannel          connection;

    public TestItClient(ManagedServerChannel c) {
        this.connection = c;
        client = TestItGrpc.newBlockingStub(c);
    }

    @Override
    public void close() throws IOException {
        connection.release();
    }

    @Override
    public Member getMember() {
        return connection.getMember();
    }

    @Override
    public Any ping(Any request) {
        return client.ping(request);
    }
}
