package org.quickfixj.jms;

import java.io.FileInputStream;
import java.util.concurrent.CountDownLatch;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.naming.InitialContext;

import quickfix.DefaultMessageFactory;
import quickfix.FileLogFactory;
import quickfix.FileStoreFactory;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;

public final class JmsFixBridgeServer implements AutoCloseable {
    private final SocketInitiator initiator;
    private final Connection jmsConnection;
    private final Session consumerSession;
    private final MessageConsumer consumer;
    private final InboundMessagePublisher publisher;

    private JmsFixBridgeServer(SocketInitiator initiator, Connection jmsConnection,
            Session consumerSession, MessageConsumer consumer,
            InboundMessagePublisher publisher) {
        this.initiator = initiator;
        this.jmsConnection = jmsConnection;
        this.consumerSession = consumerSession;
        this.consumer = consumer;
        this.publisher = publisher;
    }

    public static JmsFixBridgeServer create(BridgeConfiguration configuration) throws Exception {
        InitialContext context = new InitialContext();
        ConnectionFactory connectionFactory = (ConnectionFactory) context.lookup(
                configuration.required(BridgeConfiguration.CONNECTION_FACTORY));
        Destination outbound = (Destination) context.lookup(
                configuration.required(BridgeConfiguration.OUTBOUND_DESTINATION));
        Destination inbound = (Destination) context.lookup(
                configuration.required(BridgeConfiguration.INBOUND_DESTINATION));
        SessionSettings settings;
        try (FileInputStream input = new FileInputStream(
                configuration.required(BridgeConfiguration.FIX_SETTINGS))) {
            settings = new SessionSettings(input);
        }
        return create(connectionFactory, outbound, inbound, settings,
                configuration.requireFixLogon());
    }

    static JmsFixBridgeServer create(ConnectionFactory connectionFactory, Destination outbound,
            Destination inbound, SessionSettings settings, boolean requireFixLogon)
            throws Exception {
        Connection connection = connectionFactory.createConnection();
        Session consumerSession = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageProducer producer = producerSession.createProducer(inbound);
        RawFixMessageCodec codec = new RawFixMessageCodec();
        InboundMessagePublisher publisher = new InboundMessagePublisher(producerSession, producer,
                codec);

        FixApplication application = new FixApplication();
        application.setPublisher(publisher);
        SocketInitiator initiator = new SocketInitiator(application, new FileStoreFactory(settings),
                settings, new FileLogFactory(settings), new DefaultMessageFactory());

        MessageConsumer consumer = consumerSession.createConsumer(outbound);
        consumer.setMessageListener(new OutboundMessageConsumer(codec, requireFixLogon));
        return new JmsFixBridgeServer(initiator, connection, consumerSession, consumer, publisher);
    }

    public void start() throws Exception {
        initiator.start();
        jmsConnection.start();
    }

    @Override
    public void close() throws Exception {
        consumer.close();
        initiator.stop();
        consumerSession.close();
        publisher.close();
        jmsConnection.close();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: " + JmsFixBridgeServer.class.getName()
                    + " <bridge.properties>");
            System.exit(1);
        }
        final JmsFixBridgeServer server = create(BridgeConfiguration.load(args[0]));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "quickfixj-jms-bridge-shutdown"));
        server.start();
        new CountDownLatch(1).await();
    }
}
