package io.opensensors.sse.client.impl;

import io.opensensors.sse.client.EventSourceHandler;
import io.opensensors.sse.client.MessageEvent;

import java.util.concurrent.Executor;

public class AsyncEventSourceHandler implements EventSourceHandler {
    private final Executor executor;
    private final EventSourceHandler eventSourceHandler;

    public AsyncEventSourceHandler(Executor executor, EventSourceHandler eventSourceHandler) {
        this.executor = executor;
        this.eventSourceHandler = eventSourceHandler;
    }

    @Override
    public void onConnect() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    eventSourceHandler.onConnect();
                } catch (Exception e) {
                    onError(e);
                }
            }
        });
    }

    @Override
    public void onMessage(final String event, final MessageEvent message) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    eventSourceHandler.onMessage(event, message);
                } catch (Exception e) {
                    onError(e);
                }
            }
        });
    }

    @Override
    public void onError(final Throwable error) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    eventSourceHandler.onError(error);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
