package org.quickfixj.jms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import quickfix.ApplicationAdapter;
import quickfix.DefaultMessageFactory;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.ScreenLogFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.field.ClOrdID;
import quickfix.field.Headline;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;

class JmsFixBridgeIntegrationTest {
    private static final String BROKER_URL = "vm://quickfixj-jms-test?create=false";

    @TempDir
    Path temporaryDirectory;

    @Test
    void transportsMessagesInBothDirections() throws Exception {
        int fixPort = availablePort();
        BrokerService broker = startBroker();
        SocketAcceptor acceptor = null;
        JmsFixBridgeServer bridge = null;
        Connection testConnection = null;
        try {
            CountDownLatch orderReceived = new CountDownLatch(1);
            SessionID acceptorSessionID = new SessionID("FIX.4.4", "FIX_SERVER", "MY_BRIDGE");
            acceptor = createAcceptor(fixPort, acceptorSessionID, orderReceived);
            acceptor.start();

            ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(BROKER_URL);
            Queue outbound = new org.apache.activemq.command.ActiveMQQueue("fix.outbound");
            Queue inbound = new org.apache.activemq.command.ActiveMQQueue("fix.inbound");
            SessionID initiatorSessionID = new SessionID("FIX.4.4", "MY_BRIDGE", "FIX_SERVER");
            bridge = JmsFixBridgeServer.create(connectionFactory, outbound, inbound,
                    initiatorSettings(fixPort, initiatorSessionID), true);
            bridge.start();

            testConnection = connectionFactory.createConnection();
            Session jmsSession = testConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer outboundProducer = jmsSession.createProducer(outbound);
            MessageConsumer inboundConsumer = jmsSession.createConsumer(inbound);
            testConnection.start();

            assertTrue(waitForLogon(initiatorSessionID, 10), "FIX initiator did not log on");
            quickfix.fix44.NewOrderSingle order = new quickfix.fix44.NewOrderSingle(
                    new ClOrdID("ORDER-1"), new Side(Side.BUY),
                    new TransactTime(LocalDateTime.now()), new OrdType(OrdType.MARKET));
            order.set(new Symbol("TEST"));
            order.set(new OrderQty(10));

            TextMessage outboundMessage = jmsSession.createTextMessage(order.toString());
            outboundMessage.setStringProperty(OutboundMessageConsumer.FIX_SESSION_ID,
                    initiatorSessionID.toString());
            outboundProducer.send(outboundMessage);

            assertTrue(orderReceived.await(10, TimeUnit.SECONDS),
                    "The FIX acceptor did not receive the JMS order");
            TextMessage inboundMessage = (TextMessage) inboundConsumer.receive(10000);
            assertNotNull(inboundMessage, "The FIX response was not published to JMS");
            Message decodedResponse = new Message(inboundMessage.getText());
            assertEquals(quickfix.fix44.News.MSGTYPE,
                    decodedResponse.getHeader().getString(quickfix.field.MsgType.FIELD));
            assertEquals(initiatorSessionID.toString(), inboundMessage.getStringProperty(
                    OutboundMessageConsumer.FIX_SESSION_ID));
        } finally {
            if (testConnection != null) {
                testConnection.close();
            }
            if (bridge != null) {
                bridge.close();
            }
            if (acceptor != null) {
                acceptor.stop(true);
            }
            broker.stop();
            broker.waitUntilStopped();
        }
    }

    private SocketAcceptor createAcceptor(int port, SessionID sessionID,
            CountDownLatch orderReceived) throws Exception {
        SessionSettings settings = commonSettings();
        settings.setString("ConnectionType", "acceptor");
        settings.setLong(sessionID, "SocketAcceptPort", port);
        ApplicationAdapter application = new ApplicationAdapter() {
            @Override
            public void fromApp(Message message, SessionID currentSessionID) {
                orderReceived.countDown();
                try {
                    quickfix.Session.sendToTarget(
                            new quickfix.fix44.News(new Headline("ORDER RECEIVED")),
                            currentSessionID);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        return new SocketAcceptor(application, new MemoryStoreFactory(), settings,
                new ScreenLogFactory(false, false, false), new DefaultMessageFactory());
    }

    private SessionSettings initiatorSettings(int port, SessionID sessionID) {
        SessionSettings settings = commonSettings();
        settings.setString("ConnectionType", "initiator");
        settings.setString("FileStorePath", temporaryDirectory.resolve("store").toString());
        settings.setString("FileLogPath", temporaryDirectory.resolve("log").toString());
        settings.setString(sessionID, "SocketConnectHost", "localhost");
        settings.setLong(sessionID, "SocketConnectPort", port);
        settings.setLong(sessionID, "ReconnectInterval", 1);
        return settings;
    }

    private SessionSettings commonSettings() {
        SessionSettings settings = new SessionSettings();
        settings.setString("StartTime", "00:00:00");
        settings.setString("EndTime", "00:00:00");
        settings.setString("HeartBtInt", "30");
        settings.setString("UseDataDictionary", "N");
        return settings;
    }

    private boolean waitForLogon(SessionID sessionID, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        while (System.currentTimeMillis() < deadline) {
            quickfix.Session session = quickfix.Session.lookupSession(sessionID);
            if (session != null && session.isLoggedOn()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private BrokerService startBroker() throws Exception {
        BrokerService broker = new BrokerService();
        broker.setBrokerName("quickfixj-jms-test");
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.start();
        broker.waitUntilStarted();
        return broker;
    }

    private int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
