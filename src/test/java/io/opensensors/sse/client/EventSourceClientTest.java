package io.opensensors.sse.client;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.webbitserver.EventSourceConnection;
import org.webbitserver.EventSourceMessage;
import org.webbitserver.WebServer;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.*;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.webbitserver.WebServers.createWebServer;

public class EventSourceClientTest {
    public static final int SERVER_PORT = 59504;
    private WebServer webServer;
    private EventSource eventSource;

    @Before
    public void createServer() {
        webServer = createWebServer(SERVER_PORT);
    }

    @After
    public void die() throws IOException, InterruptedException, TimeoutException, ExecutionException {
        eventSource.close().join();
        webServer.stop().get(5, TimeUnit.SECONDS);
    }

    @Test
    public void canSendAndReadTwoSingleLineMessages() throws Exception {
        assertSentAndReceived(asList("a", "b"));
    }

    @Test
    public void canSendAndReadThreeSingleLineMessages() throws Exception {
        assertSentAndReceived(asList("C", "D", "E"));
    }

    @Test
    public void canSendAndReadOneMultiLineMessages() throws Exception {
        assertSentAndReceived(asList("f\ng\nh"));
    }

    @Test
    public void reconnectsIfServerIsDownAtCreationTime() throws Exception {
        List<String> messages = asList("a", "b");
        CountDownLatch messageCountdown = new CountDownLatch(messages.size());
        CountDownLatch errorCountdown = new CountDownLatch(1);
        startClient(messages, messageCountdown, errorCountdown, 100);
        startServer(messages);
        assertTrue("Didn't get an error on first failed connection", errorCountdown.await(2, TimeUnit.SECONDS));
        assertTrue("Didn't get all messages", messageCountdown.await(3, TimeUnit.SECONDS));
    }

    @Test
    @Ignore // Because of https://github.com/webbit/webbit/issues/29
    public void reconnectsIfServerGoesDownAfterConnectionEstablished() throws Exception {
        final CountDownLatch messageOneCountdown = new CountDownLatch(1);
        final CountDownLatch messageTwoCountdown = new CountDownLatch(1);
        final CountDownLatch errorCountdown = new CountDownLatch(1);

        webServer
                .add("/es/.*", new org.webbitserver.EventSourceHandler() {
                    public int counter = 1;

                    @Override
                    public void onOpen(EventSourceConnection connection) throws Exception {
                        connection.send(new EventSourceMessage(Integer.toString(counter++)));
                    }

                    @Override
                    public void onClose(EventSourceConnection connection) throws Exception {
                    }
                });
        webServer.start();


        eventSource = new EventSource(Executors.newSingleThreadExecutor(), 100, URI.create("http://localhost:59504/es/hello"), new EventSourceHandler() {
            @Override
            public void onConnect() {
            }

            @Override
            public void onMessage(String event, MessageEvent message) throws IOException {
                if (message.data.equals("1")) {
                    messageOneCountdown.countDown();
                } else if (message.data.equals("2")) {
                    messageTwoCountdown.countDown();
                } else {
                    throw new RuntimeException("Bad message");
                }
            }

            @Override
            public void onError(Throwable t) {
                System.out.println("ERROR: " + t);
                errorCountdown.countDown();
            }
        });
        eventSource.connect();

        assertTrue("Didn't get 1st message", messageOneCountdown.await(1000, TimeUnit.MILLISECONDS));

        System.out.println("Stopping server..");
        webServer.stop().get(5, TimeUnit.SECONDS);
        System.out.println("Stopped");
        System.out.println("KILLED");

        assertTrue("Didn't get an error on first failed connection", errorCountdown.await(1000, TimeUnit.MILLISECONDS));

        webServer.start();
        assertTrue("Didn't get all messages", messageTwoCountdown.await(1000, TimeUnit.MILLISECONDS));
    }

    private void assertSentAndReceived(final List<String> messages) throws IOException, InterruptedException,
        TimeoutException, ExecutionException {
        startServer(messages);
        CountDownLatch messageCountdown = new CountDownLatch(messages.size());
        startClient(messages, messageCountdown, new CountDownLatch(0), 5000);
        assertTrue("Didn't get all messages", messageCountdown.await(1000, TimeUnit.MILLISECONDS));
    }

    private void startClient(final List<String> expectedMessages,
                             final CountDownLatch messageCountdown,
                             final CountDownLatch errorCountdown,
                             long reconnectionTimeMillis) throws InterruptedException {
        eventSource = new EventSource(Executors.newSingleThreadExecutor(),
                                      reconnectionTimeMillis,
                                      URI.create("http://localhost:"  + SERVER_PORT + "/es/hello?echoThis=yo"),
                                      new EventSourceHandler() {
            int n = 0;

            @Override
            public void onConnect() {
                System.out.println("Client connected");
            }

            @Override
            public void onMessage(String event, MessageEvent message) {
                assertEquals(expectedMessages.get(n++) + " yo", message.data);
                assertEquals("http://localhost:" + SERVER_PORT + "/es/hello?echoThis=yo", message.origin);
                messageCountdown.countDown();
            }

            @Override
            public void onError(Throwable t) {
                errorCountdown.countDown();
            }
        });
        try {
            eventSource.connect();
        } catch (Throwable t) {
            errorCountdown.countDown();
        }
    }

    private void startServer(final List<String> messagesToSend) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        webServer
                .add("/es/.*", new org.webbitserver.EventSourceHandler() {
                    @Override
                    public void onOpen(EventSourceConnection connection) throws Exception {
                        System.out.println("Accepted a new EventSource connection");
                        for (String message : messagesToSend) {
                            String data = message + " " + connection.httpRequest().queryParam("echoThis");
                            connection.send(new EventSourceMessage(data));
                        }
                    }

                    @Override
                    public void onClose(EventSourceConnection connection) throws Exception {
                    }
                })
                .start().
                get(4, TimeUnit.SECONDS);
        System.out.println("SSE server is listening on " + webServer.getUri());
    }
}
