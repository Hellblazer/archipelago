/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.comm.grpc;

import java.io.IOException;
import java.util.concurrent.Executor;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategyFactory;
import io.netty.channel.kqueue.KQueueDomainSocketChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerDomainSocketChannel;
import io.netty.channel.unix.PeerCredentials;
import io.netty.channel.unix.ServerDomainSocketChannel;
import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.concurrent.RejectedExecutionHandler;

/**
 * @author hal.hildebrand
 *
 */
public class DomainSockets {
    static {
        try {
            final var sock = new KQueueServerDomainSocketChannel();
            sock.close();
        } catch (Throwable t) {
//            t.printStackTrace();
        }
    }

    public static Class<? extends Channel> getChannelType() {
        return KQueueDomainSocketChannel.class;
    }

    public static EventLoopGroup getEventLoopGroup() {
        return new KQueueEventLoopGroup();
    }

    public static EventLoopGroup getEventLoopGroup(int threads) {
        return new KQueueEventLoopGroup(threads);
    }

    public static EventLoopGroup getEventLoopGroup(int threads, Executor executor) {
        return new KQueueEventLoopGroup(threads, executor);
    }

    public static EventLoopGroup getEventLoopGroup(int threads, Executor executor,
                                                   EventExecutorChooserFactory chooserFactory,
                                                   SelectStrategyFactory selectStrategyFactory) {
        return new KQueueEventLoopGroup(threads, executor, chooserFactory, selectStrategyFactory);
    }

    public static EventLoopGroup getEventLoopGroup(int threads, Executor executor,
                                                   EventExecutorChooserFactory chooserFactory,
                                                   SelectStrategyFactory selectStrategyFactory,
                                                   RejectedExecutionHandler rejectedExecutionHandler) {
        return new KQueueEventLoopGroup(threads, executor, chooserFactory, selectStrategyFactory,
                                        rejectedExecutionHandler);
    }

    public static EventLoopGroup getEventLoopGroup(int threads, Executor executor,
                                                   EventExecutorChooserFactory chooserFactory,
                                                   SelectStrategyFactory selectStrategyFactory,
                                                   RejectedExecutionHandler rejectedExecutionHandler,
                                                   EventLoopTaskQueueFactory queueFactory) {
        return new KQueueEventLoopGroup(threads, executor, chooserFactory, selectStrategyFactory,
                                        rejectedExecutionHandler, queueFactory);
    }

    public static EventLoopGroup getEventLoopGroup(int threads, Executor executor,
                                                   SelectStrategyFactory selectStrategyFactory) {
        return new KQueueEventLoopGroup(threads, executor, selectStrategyFactory);
    }

    public static PeerCredentials getPeerCredentials(Channel channel) {
        if (channel instanceof KQueueDomainSocketChannel ep) {
            try {
                return ep.peerCredentials();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot get peer credentials for: " + channel, e);
            }
        } else {
            throw new IllegalStateException("Cannot get peer credentials (unsupported) for: " + channel);
        }
    }

    public static Class<? extends ServerDomainSocketChannel> getServerDomainSocketChannelClass() {
        return KQueueServerDomainSocketChannel.class;
    }
}
