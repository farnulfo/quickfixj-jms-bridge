# QuickFIX/J JMS Bridge

Standalone bridge between JMS destinations and QuickFIX/J sessions.

The bridge consumes raw FIX messages from a JMS destination, sends them through
a QuickFIX/J initiator session, and publishes inbound FIX messages to another
JMS destination.

## Requirements

- JDK 8 or newer
- a JMS 2.0 provider accessible through JNDI
- a remote FIX acceptor

## Build

```bash
./mvnw clean verify
```

## Run

Copy and adapt the example configuration files:

```bash
cp src/main/resources/bridge.properties.example bridge.properties
cp src/main/resources/quickfixj.cfg.example quickfixj.cfg
```

The JMS provider implementation and its JNDI configuration must be present on
the runtime classpath. Then start the bridge with:

```bash
java -cp "target/quickfixj-jms-bridge-0.1.0-SNAPSHOT.jar:<runtime-dependencies>" \
  org.quickfixj.jms.JmsFixBridgeServer bridge.properties
```

The complete design and reliability considerations are documented in
[`docs/architecture.md`](docs/architecture.md).

