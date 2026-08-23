package org.quickfixj.jms;

import javax.jms.JMSException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

public final class FixApplication implements Application {
    private static final Logger LOG = LoggerFactory.getLogger(FixApplication.class);

    private volatile InboundMessagePublisher publisher;

    public void setPublisher(InboundMessagePublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void onCreate(SessionID sessionId) {
        LOG.info("FIX session created: {}", sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        LOG.info("FIX session logged on: {}", sessionId);
    }

    @Override
    public void onLogout(SessionID sessionId) {
        LOG.info("FIX session logged out: {}", sessionId);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) throws FieldNotFound,
            IncorrectDataFormat, IncorrectTagValue, RejectLogon {
    }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
    }

    @Override
    public void fromApp(Message message, SessionID sessionId) throws FieldNotFound,
            IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        InboundMessagePublisher currentPublisher = publisher;
        if (currentPublisher == null) {
            LOG.error("Dropping inbound FIX message because the JMS publisher is not ready: {}",
                    sessionId);
            return;
        }
        try {
            currentPublisher.publish(message, sessionId);
        } catch (JMSException e) {
            throw new RuntimeException("Unable to publish inbound FIX message to JMS", e);
        }
    }
}
