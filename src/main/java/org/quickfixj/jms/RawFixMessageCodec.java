package org.quickfixj.jms;

import quickfix.InvalidMessage;
import quickfix.Message;
import quickfix.MessageSessionUtils;
import quickfix.Session;

public final class RawFixMessageCodec {
    static final char SOH = '\001';

    public Message decode(String value, Session session) throws InvalidMessage {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidMessage("The JMS message contains no FIX payload");
        }
        return MessageSessionUtils.parse(session, normalize(value));
    }

    public String encode(Message message) {
        return message.toString();
    }

    static String normalize(String value) {
        return value.indexOf(SOH) >= 0 ? value : value.replace('|', SOH);
    }
}
