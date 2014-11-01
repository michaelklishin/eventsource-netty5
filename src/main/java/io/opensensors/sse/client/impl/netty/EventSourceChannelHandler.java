package io.opensensors.sse.client.impl.netty;

import io.opensensors.sse.client.EventSourceException;
import io.opensensors.sse.client.EventSourceHandler;
import io.opensensors.sse.client.impl.ConnectionHandler;
import io.opensensors.sse.client.impl.EventStreamParser;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.*;

import java.net.ConnectException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EventSourceChannelHandler extends SimpleChannelInboundHandler<String> implements ConnectionHandler {
    private static final Pattern STATUS_PATTERN = Pattern.compile("HTTP/1.1 (\\d+) (.*)");
    private static final Pattern CONTENT_TYPE_PATTERN = Pattern.compile("Content-Type: text/event-stream");

    private final EventSourceHandler eventSourceHandler;
    private final Bootstrap bootstrap;
    private final URI uri;
    private final EventStreamParser messageDispatcher;

    private final Timer timer = new HashedWheelTimer();
    private Channel channel;
    private boolean reconnectOnClose = true;
    private long reconnectionTimeMillis;
    private String lastEventId;
    private boolean eventStreamOk;
    private boolean headerDone;
    private Integer status;
    private AtomicBoolean reconnecting = new AtomicBoolean(false);

    public EventSourceChannelHandler(EventSourceHandler eventSourceHandler, long reconnectionTimeMillis, Bootstrap bootstrap, URI uri) {
        this.eventSourceHandler = eventSourceHandler;
        this.reconnectionTimeMillis = reconnectionTimeMillis;
        this.bootstrap = bootstrap;
        this.uri = uri;
        this.messageDispatcher = new EventStreamParser(uri.toString(), eventSourceHandler, this);
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri.toString());
        request.headers().add(Names.ACCEPT, "text/event-stream");
        request.headers().add(Names.HOST, uri.getHost());
        request.headers().add(Names.ORIGIN, "http://" + uri.getHost());
        request.headers().add(Names.CACHE_CONTROL, "no-cache");

        if (lastEventId != null) {
            request.headers().add("Last-Event-ID", lastEventId);
        }

        channel = context.channel();
        channel.writeAndFlush(request);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        channel = null;
        if (reconnectOnClose) {
            reconnect();
        }
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, String line) throws Exception {
        if (status == null) {
            Matcher statusMatcher = STATUS_PATTERN.matcher(line);
            if (statusMatcher.matches()) {
                status = Integer.parseInt(statusMatcher.group(1));
                if (status != 200) {
                    eventSourceHandler.onError(new EventSourceException("Bad status from " + uri + ": " + status));
                    reconnect();
                }
                return;
            } else {
                eventSourceHandler.onError(new EventSourceException("Not HTTP? " + uri + ": " + line));
                reconnect();
            }
        }
        if (!headerDone) {
            if (CONTENT_TYPE_PATTERN.matcher(line).matches()) {
                eventStreamOk = true;
            }
            if (line.isEmpty()) {
                headerDone = true;
                if (eventStreamOk) {
                    eventSourceHandler.onConnect();
                } else {
                    eventSourceHandler.onError(new EventSourceException("Not event stream: " + uri + " (expected Content-Type: text/event-stream"));
                    reconnect();
                }
            }
        } else {
            messageDispatcher.line(line);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable error) throws Exception {
        System.out.println("in exceptionCaught");
        if(error instanceof ConnectException) {
            error = new EventSourceException("Failed to connect to " + uri, error);
        }
        eventSourceHandler.onError(error);
        context.channel().close();
    }

    public void setReconnectionTimeMillis(long reconnectionTimeMillis) {
        this.reconnectionTimeMillis = reconnectionTimeMillis;
    }

    @Override
    public void setLastEventId(String lastEventId) {
        this.lastEventId = lastEventId;
    }

    public EventSourceChannelHandler close() {
        reconnectOnClose = false;
        if (channel != null) {
            channel.close();
        }
        return this;
    }

    public EventSourceChannelHandler join() throws InterruptedException {
        if (channel != null) {
            channel.closeFuture().await();
        }
        return this;
    }

    private void reconnect() {
        if(!reconnecting.get()) {
            reconnecting.set(true);
            timer.newTimeout(new TimerTask() {
                @Override
                public void run(Timeout timeout) throws Exception {
                    reconnecting.set(false);
                    bootstrap.connect().await();
                }
            }, reconnectionTimeMillis, TimeUnit.MILLISECONDS);
        }
    }
}
