package example;

import replicated.messaging.*;
import replicated.network.NioNetwork;
import replicated.network.Network;
import replicated.network.MessageContext;

public class EchoServer implements MessageHandler {
    private final Network network;
    private final MessageBus messageBus;
    private final NetworkAddress myAddress;
    private final JsonMessageCodec codec;
    private volatile boolean running = false;

    public EchoServer(String host, int port) {
        this.myAddress = new NetworkAddress(host, port);
        this.codec = new JsonMessageCodec();
        this.network = new NioNetwork(codec);
        ((NioNetwork) network).bind(myAddress);
        this.messageBus = new MessageBus(network, codec);
        network.registerMessageHandler(messageBus);
        messageBus.registerHandler(myAddress, this);
    }

    @Override
    public void onMessageReceived(Message message, MessageContext ctx) {
        if (message.messageType() == ExampleMessageTypes.ECHO_REQUEST) {
            EchoRequest req = codec.decode(message.payload(), EchoRequest.class);
            System.out.println("EchoServer received: " + req.message());
            EchoResponse resp = new EchoResponse(req.message());
            Message response = new Message(myAddress, message.source(), ExampleMessageTypes.ECHO_RESPONSE,
                    codec.encode(resp), message.correlationId());
            messageBus.reply(ctx, response);
        }
    }

    public void tick() {
        network.tick();
        messageBus.tick();
    }

    public void runLoop() {
        running = true;
        while (running) {
            tick();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    public static void main(String[] args) {
        int port = 9100;
        if (args.length > 0) port = Integer.parseInt(args[0]);
        EchoServer server = new EchoServer("127.0.0.1", port);
        System.out.println("EchoServer started on port " + port);
        server.runLoop();
    }
} 