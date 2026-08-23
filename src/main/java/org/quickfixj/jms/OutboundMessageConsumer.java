package org.quickfixj.jms;

import javax.jms.JMSException;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;

public final class OutboundMessageConsumer implements MessageListener {
    public static final String FIX_SESSION_ID = "fixSessionId";

    private static final Logger LOG = LoggerFactory.getLogger(OutboundMessageConsumer.class);

    private final RawFixMessageCodec codec;
    private final boolean requireFixLogon;

    public OutboundMessageConsumer(RawFixMessageCodec codec, boolean requireFixLogon) {
        this.codec = codec;
        this.requireFixLogon = requireFixLogon;
    }

    @Override
    public void onMessage(javax.jms.Message jmsMessage) {
        try {
            if (!(jmsMessage instanceof TextMessage)) {
                throw new JMSException("Only JMS TextMessage payloads are supported");
            }
            String sessionIdValue = jmsMessage.getStringProperty(FIX_SESSION_ID);
            if (sessionIdValue == null || sessionIdValue.trim().isEmpty()) {
                throw new JMSException("Missing JMS property: " + FIX_SESSION_ID);
            }

            SessionID sessionID = new SessionID(sessionIdValue);
            Session fixSession = Session.lookupSession(sessionID);
            if (fixSession == null) {
                throw new JMSException("Unknown FIX session: " + sessionID);
            }
            if (requireFixLogon && !fixSession.isLoggedOn()) {
                throw new JMSException("FIX session is not logged on: " + sessionID);
            }

            Message fixMessage = codec.decode(((TextMessage) jmsMessage).getText(), fixSession);
            if (!fixSession.send(fixMessage)) {
                throw new JMSException("QuickFIX/J rejected message for session: " + sessionID);
            }
            jmsMessage.acknowledge();
            LOG.debug("Sent JMS message {} to FIX session {}", jmsMessage.getJMSMessageID(),
                    sessionID);
        } catch (Exception e) {
            LOG.error("Unable to deliver JMS message to FIX", e);
            throw new RuntimeException(e);
        }
    }
}
