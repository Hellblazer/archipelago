package com.hellblazer.archipeligo.ring;

import com.google.protobuf.Any;
import com.hellblazer.archipelago.Link;

import java.io.Closeable;

/**
 * @author hal.hildebrand
 **/
public interface TestItService extends Link, Closeable {
    Any ping(Any request);
}
