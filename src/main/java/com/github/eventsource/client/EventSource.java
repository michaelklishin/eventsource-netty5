package com.github.eventsource.client;

import com.github.eventsource.client.impl.AsyncEventSourceHandler;
import com.github.eventsource.client.impl.netty.EventSourceChannelHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.string.StringDecoder;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class EventSource  {
    public static final long DEFAULT_RECONNECTION_TIME_MILLIS = 2000;

    public static final int CONNECTING = 0;
    public static final int OPEN = 1;
    public static final int CLOSED = 2;

    private final Bootstrap bootstrap;
    private final EventSourceChannelHandler clientHandler;

    private int readyState;

    /**
     * Creates a new <a href="http://dev.w3.org/html5/eventsource/">EventSource</a> client. The client will reconnect on 
     * lost connections automatically, unless the connection is closed explicitly by a call to 
     * {@link com.github.eventsource.client.EventSource#close()}.
     *
     * For sample usage, see examples at <a href="https://github.com/aslakhellesoy/eventsource-java/tree/master/src/test/java/com/github/eventsource/client">GitHub</a>.
     * 
     * @param executor the executor that will receive events
     * @param reconnectionTimeMillis delay before a reconnect is made - in the event of a lost connection
     * @param uri where to connect
     * @param eventSourceHandler receives events
     * @see #close()
     */
    public EventSource(Executor executor, long reconnectionTimeMillis, final URI uri, EventSourceHandler eventSourceHandler) {
        EventLoopGroup group = new NioEventLoopGroup();

        bootstrap = new Bootstrap();

        clientHandler = new EventSourceChannelHandler(new AsyncEventSourceHandler(executor, eventSourceHandler),
                                                      reconnectionTimeMillis, bootstrap, uri);

        bootstrap.
            group(group).
            channel(NioSocketChannel.class).
            option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000).
            remoteAddress(new InetSocketAddress(uri.getHost(), uri.getPort())).
            handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel channel) throws Exception {
                    ChannelPipeline pipeline = channel.pipeline();
                    pipeline.addLast("line", new DelimiterBasedFrameDecoder(Integer.MAX_VALUE, Delimiters.lineDelimiter()));
                    pipeline.addLast("string", new StringDecoder());
                    pipeline.addLast("encoder", new HttpRequestEncoder());
                    pipeline.addLast("es-handler", clientHandler);
                }
            });
    }

    public EventSource(String uri, EventSourceHandler eventSourceHandler) {
        this(URI.create(uri), eventSourceHandler);
    }

    public EventSource(URI uri, EventSourceHandler eventSourceHandler) {
        this(Executors.newSingleThreadExecutor(), DEFAULT_RECONNECTION_TIME_MILLIS, uri, eventSourceHandler);
    }

    public ChannelFuture connect() throws InterruptedException {
        readyState = CONNECTING;
        ChannelFuture channel = bootstrap.connect();
        readyState = OPEN;
        try {
            final ChannelFuture result = channel.channel().closeFuture();
            return result;
        } finally {
            readyState = CLOSED;
        }
    }

    /**
     * Close the connection
     *
     * @return self
     */
    public EventSource close() {
        clientHandler.close();
        return this;
    }

    /**
     * Wait until the connection is closed
     *
     * @return self
     * @throws InterruptedException if waiting was interrupted
     */
    public EventSource join() throws InterruptedException {
        clientHandler.join();
        return this;
    }
}
