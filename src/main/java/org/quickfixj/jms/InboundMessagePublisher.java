package org.quickfixj.jms;

import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

import quickfix.FieldNotFound;
import quickfix.SessionID;

public final class InboundMessagePublisher implements AutoCloseable {
    private final Session jmsSession;
    private final MessageProducer producer;
    private final RawFixMessageCodec codec;

    public InboundMessagePublisher(Session jmsSession, MessageProducer producer,
            RawFixMessageCodec codec) {
        this.jmsSession = jmsSession;
        this.producer = producer;
        this.codec = codec;
    }

    public synchronized void publish(quickfix.Message message, SessionID sessionID)
            throws JMSException, FieldNotFound {
        TextMessage jmsMessage = jmsSession.createTextMessage(codec.encode(message));
        jmsMessage.setStringProperty(OutboundMessageConsumer.FIX_SESSION_ID, sessionID.toString());
        jmsMessage.setStringProperty("fixMessageType",
                message.getHeader().getString(quickfix.field.MsgType.FIELD));
        producer.send(jmsMessage);
    }

    @Override
    public void close() throws JMSException {
        producer.close();
        jmsSession.close();
    }
}
