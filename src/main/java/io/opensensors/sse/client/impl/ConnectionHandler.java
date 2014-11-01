package io.opensensors.sse.client.impl;

public interface ConnectionHandler {
    void setReconnectionTimeMillis(long reconnectionTimeMillis);
    void setLastEventId(String lastEventId);
}
