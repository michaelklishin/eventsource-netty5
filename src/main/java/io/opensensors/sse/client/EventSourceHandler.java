package io.opensensors.sse.client;

public interface EventSourceHandler {
    void onConnect() throws Exception;
    void onMessage(String event, MessageEvent message) throws Exception;
    void onError(Throwable t);
}
