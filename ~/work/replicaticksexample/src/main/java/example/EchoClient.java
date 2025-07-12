package example;

import replicated.client.ClusterClient;
import replicated.future.ListenableFuture;
import replicated.messaging.JsonMessageCodec;
import replicated.messaging.MessageBus;
import replicated.messaging.Message;
import replicated.messaging.MessageHandler;
import replicated.messaging.MessageTypeInterface;
import replicated.messaging.NetworkAddress;
import replicated.network.Network;
import replicated.network.NioNetwork;
import replicated.network.MessageContext;

import java.util.List;

public class EchoClient implements MessageHandler {
    private final ClusterClient clusterClient;
    private final Network network;
    private final MessageBus messageBus;

    public EchoClient(NetworkAddress serverAddress) {
        JsonMessageCodec codec = new JsonMessageCodec();
        this.network = new NioNetwork(codec);
        this.messageBus = new MessageBus(network, codec);
        this.network.registerMessageHandler(messageBus);

        this.clusterClient = new ClusterClient(messageBus, codec, List.of(serverAddress), "echo-client", 200);
        this.clusterClient.setMessageHandler(this);
    }

    public ListenableFuture<String> sendEcho(String text) {
        EchoRequest req = new EchoRequest(text);
        return clusterClient.sendRequestWithFailover(req, ExampleMessageTypes.ECHO_REQUEST, response -> {
            if (response instanceof EchoResponse er) {
                return er.message();
            } else {
                throw new RuntimeException("Unexpected response type: " + response);
            }
        });
    }

    @Override
    public void onMessageReceived(Message message, MessageContext ctx) {
        if (message.messageType() == ExampleMessageTypes.ECHO_RESPONSE) {
            EchoResponse resp = clusterClient.deserializePayload(message.payload(), EchoResponse.class);
            clusterClient.getRequestWaitingList().handleResponse(message.correlationId(), resp, message.source());
        } else {
            // Ignore other messages
        }
    }

    public void tick() {
        clusterClient.tick();
        messageBus.tick();
        network.tick();
    }

    public static void main(String[] args) throws Exception {
        NetworkAddress serverAddr = new NetworkAddress("127.0.0.1", 9100);
        EchoClient client = new EchoClient(serverAddr);

        ListenableFuture<String> future = client.sendEcho("Hello Replicaticks");

        int ticks = 0;
        while (future.isPending() && ticks < 10000) {
            client.tick();
            Thread.sleep(1);
            ticks++;
        }

        if (future.isCompleted()) {
            System.out.println("Received echo: " + future.getResult());
        } else {
            System.out.println("Echo request failed or timed out.");
        }
    }
} 