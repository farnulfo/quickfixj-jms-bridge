# QuickFIX/J JMS Bridge

Passerelle autonome entre des destinations JMS et des sessions QuickFIX/J.

Ce projet est indépendant du dépôt QuickFIX/J. Il utilise les artefacts publiés
sur Maven Central et peut donc être compilé sans cloner ni construire les
sources de QuickFIX/J.

La passerelle :

- consomme des messages FIX bruts depuis une destination JMS ;
- les envoie au moyen d'une session initiatrice QuickFIX/J ;
- reçoit les messages FIX entrants ;
- les publie vers une seconde destination JMS.

## État du projet

Il s'agit d'une première implémentation fonctionnelle. Le transport nominal est
couvert par un test d'intégration avec un broker ActiveMQ embarqué et un
accepteur FIX local. Les mécanismes avancés décrits dans l'architecture (DLQ,
déduplication persistante et file interne bornée) restent à implémenter.

## Prérequis

- JDK 8 ou plus récent ;
- un fournisseur JMS 2.0 accessible par JNDI pour l'exécution ;
- un accepteur FIX distant.

Le projet dépend de QuickFIX/J `3.0.2` et de l'API JMS `javax.jms` 2.0.1. Le
broker ActiveMQ n'est utilisé que par les tests.

## Compiler et tester

Le projet fournit son propre Maven Wrapper :

```bash
git clone https://github.com/farnulfo/quickfixj-jms-bridge.git
cd quickfixj-jms-bridge
./mvnw clean verify
```

Sous Windows :

```bat
mvnw.cmd clean verify
```

## Configuration

Copier puis adapter les fichiers d'exemple :

```bash
cp src/main/resources/bridge.properties.example bridge.properties
cp src/main/resources/quickfixj.cfg.example quickfixj.cfg
```

`bridge.properties` indique les noms JNDI de la fabrique de connexions et des
deux destinations JMS, ainsi que le chemin de la configuration QuickFIX/J :

```properties
jms.connectionFactory=java:/jms/ConnectionFactory
jms.outboundDestination=java:/jms/queue/fix.outbound
jms.inboundDestination=java:/jms/queue/fix.inbound
bridge.fixSettings=quickfixj.cfg
bridge.requireFixLogon=true
```

Le fournisseur JMS et sa configuration JNDI doivent être ajoutés au classpath
d'exécution. La dépendance exacte dépend du broker choisi.

## Exécuter

Préparer les dépendances déclarées par Maven :

```bash
./mvnw package dependency:copy-dependencies -DskipTests
```

Ajouter au classpath les JAR du fournisseur JMS et sa configuration JNDI, puis
démarrer la passerelle :

```bash
java -cp "target/quickfixj-jms-bridge-0.1.0-SNAPSHOT.jar:target/dependency/*:<jms-provider-jars>" \
  org.quickfixj.jms.JmsFixBridgeServer bridge.properties
```

Sous Windows, remplacer les séparateurs `:` du classpath par `;`.

## Format JMS

Le corps doit être un `TextMessage` contenant un message FIX brut avec le
séparateur SOH. La propriété JMS `fixSessionId` désigne la session cible, par
exemple :

```text
FIX.4.4:MY_BRIDGE->FIX_SERVER
```

Les messages reçus depuis FIX sont publiés comme `TextMessage` sur la
destination entrante et portent également la propriété `fixSessionId`.

## Documentation

Les choix d'architecture, les garanties de livraison et la feuille de route
sont détaillés dans [docs/architecture.md](docs/architecture.md).

