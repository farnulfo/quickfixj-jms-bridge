# Passerelle JMS vers QuickFIX/J

## Objectif

L'objectif est de construire un serveur Java jouant le rôle de passerelle entre des applications métier utilisant JMS et un serveur FIX distant.

La passerelle doit :

- démarrer un moteur QuickFIX/J ;
- se connecter au serveur FIX avec un `SocketInitiator` ;
- consommer les messages à envoyer depuis une destination JMS ;
- convertir ces messages en messages FIX ;
- envoyer les messages au serveur FIX ;
- recevoir les réponses FIX ;
- publier ces réponses sur une destination JMS ;
- gérer les erreurs, les reconnexions, les redélivrances et les doublons.

La passerelle est maintenue dans le dépôt public autonome
[`farnulfo/quickfixj-jms-bridge`](https://github.com/farnulfo/quickfixj-jms-bridge).
Elle utilise uniquement les API publiques et les artefacts publiés de
QuickFIX/J. JMS reste ainsi hors de `quickfixj-core` et le cycle de publication
de la passerelle est indépendant de celui du moteur FIX.

## Architecture générale

### Envoi vers FIX

```text
Application métier
      │ JMS
      ▼
File fix.outbound
      │
      ▼
Passerelle QuickFIX/J
  ├─ consommateur JMS
  ├─ conversion JMS → Message FIX
  ├─ SocketInitiator
  └─ Application QuickFIX/J
      │
      ▼
Serveur FIX distant
```

### Réception depuis FIX

```text
Serveur FIX
      │ ExecutionReport, Reject, etc.
      ▼
Application.fromApp()
      │ conversion FIX → JMS
      ▼
File fix.inbound
      │
      ▼
Application métier
```

## Projet autonome

Le dépôt possède son propre POM, son Maven Wrapper, ses tests et sa
documentation :

```text
quickfixj-jms-bridge/
├── .mvn/wrapper/
├── docs/
│   └── architecture.md
├── mvnw
├── mvnw.cmd
├── pom.xml
├── src/main/java/org/quickfixj/jms/
│   ├── JmsFixBridgeServer.java
│   ├── BridgeConfiguration.java
│   ├── FixApplication.java
│   ├── OutboundMessageConsumer.java
│   ├── InboundMessagePublisher.java
│   └── RawFixMessageCodec.java
├── src/main/resources/
│   ├── bridge.properties.example
│   └── quickfixj.cfg.example
└── src/test/java/org/quickfixj/jms/
```

Le projet dépend directement de :

- `quickfixj-core` 3.0.2 ;
- `quickfixj-messages-all` 3.0.2 ;
- l'API JMS `javax.jms`, compatible avec Java 8 ;
- SLF4J pour l'API de journalisation.

ActiveMQ Classic est une dépendance de test uniquement. Le fournisseur JMS de
production reste au choix de l'utilisateur et doit être ajouté au classpath
d'exécution avec sa configuration JNDI.

Le projet porte sa propre version (`0.1.0-SNAPSHOT`) et ne dépend ni du POM
parent ni du réacteur de construction de QuickFIX/J.

## Flux d'envoi

Le consommateur lit un message JMS contenant au minimum :

- la session FIX cible ;
- le contenu du message ;
- un identifiant de corrélation métier ;
- éventuellement le type et le format du contenu.

Exemple conceptuel :

```json
{
  "sessionId": "FIX.4.4:SENDER->TARGET",
  "message": "8=FIX.4.4\u00019=...\u000135=D\u0001..."
}
```

La passerelle suit les étapes suivantes :

1. récupérer le message JMS ;
2. déterminer la session FIX cible ;
3. transformer le contenu en `quickfix.Message` ;
4. valider le message avec le dictionnaire de la session ;
5. appeler `Session.sendToTarget(message, sessionID)` ;
6. acquitter le message JMS lorsque QuickFIX/J l'a accepté ;
7. publier une erreur ou déplacer le message vers une DLQ si la conversion ou l'envoi échoue.

L'acquittement JMS ne prouve pas que le serveur FIX distant a accepté l'ordre. Il signifie seulement que QuickFIX/J a pris le message en charge et l'a éventuellement enregistré dans son `MessageStore`.

Le résultat métier arrive ultérieurement sous la forme d'un `ExecutionReport`, d'un `BusinessMessageReject`, d'un `OrderCancelReject` ou d'un autre message applicatif FIX.

## Flux de réception

L'implémentation de `quickfix.Application` doit rester légère :

```java
public final class FixApplication implements quickfix.Application {
    private final InboundMessagePublisher publisher;

    @Override
    public void fromApp(Message message, SessionID sessionID) {
        publisher.publish(sessionID, message);
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionID) {
        // Publication facultative sur une destination administrative.
    }

    @Override
    public void onLogon(SessionID sessionID) {
        // Publication éventuelle d'un événement de connexion.
    }

    @Override
    public void onLogout(SessionID sessionID) {
        // Publication éventuelle d'un événement de déconnexion.
    }
}
```

Une publication JMS lente ne doit pas être effectuée directement dans `fromApp()`. Cela pourrait bloquer le thread de traitement QuickFIX/J.

Le callback doit copier ou encoder rapidement le message, puis le placer dans une file interne bornée :

```text
fromApp()
   │ copie et encodage rapide
   ▼
file interne bornée
   │
   ▼
thread producteur JMS
```

La taille maximale de cette file permet d'appliquer une contre-pression lorsque le broker JMS est indisponible ou trop lent.

## Format des messages JMS

### Option 1 : message FIX brut

Le corps JMS contient le message FIX avec le séparateur SOH.

Avantages :

- aucune perte d'information ;
- conversion simple ;
- compatibilité avec toutes les versions FIX ;
- format adapté à une première version générique.

Inconvénient : les applications métier doivent connaître le protocole FIX.

### Option 2 : JSON métier

Le corps JMS décrit une commande métier indépendante de FIX :

```json
{
  "type": "NewOrder",
  "clientOrderId": "ORD-123",
  "symbol": "AAPL",
  "side": "BUY",
  "quantity": 100,
  "orderType": "LIMIT",
  "price": 185.25
}
```

Un adaptateur transforme ensuite cette commande en une classe telle que `quickfix.fix44.NewOrderSingle`.

Avantages :

- fort découplage des applications métier ;
- API plus lisible ;
- format métier pouvant rester stable malgré certaines évolutions FIX.

Inconvénients :

- davantage de code de mapping ;
- gestion explicite des différences entre les versions FIX ;
- risque de perdre des champs spécifiques au protocole ou à une contrepartie.

### Option recommandée pour la première version

Utiliser un corps FIX brut et des propriétés JMS pour le routage :

```text
JMSCorrelationID = ORD-123
fixSessionId     = FIX.4.4:SENDER->TARGET
fixMessageType   = D
contentType      = application/fix
```

Un codec JSON métier pourra être ajouté ultérieurement derrière une interface :

```java
public interface FixMessageCodec {
    Message decode(javax.jms.Message source, SessionID sessionID)
            throws MessageConversionException;

    javax.jms.Message encode(
            JMSContext context,
            Message message,
            SessionID sessionID);
}
```

## Destinations JMS

| Destination | Usage |
|---|---|
| `fix.outbound` | Messages applicatifs à envoyer au serveur FIX |
| `fix.inbound` | Messages applicatifs reçus du serveur FIX |
| `fix.events` | Logon, logout, reconnexion et état des sessions |
| `fix.admin` | Messages administratifs FIX si leur exposition est nécessaire |
| `fix.delivery` | Résultat technique de la prise en charge d'un message sortant |
| `fix.dlq` | Messages impossibles à convertir ou à router |

## Routage vers les sessions FIX

Le message JMS doit désigner explicitement la session cible, par exemple avec la propriété `fixSessionId`.

Le composant `MessageRoutingStrategy` doit :

1. lire la propriété de routage ;
2. construire ou rechercher le `SessionID` ;
3. vérifier que la session existe ;
4. vérifier son état selon la politique configurée ;
5. fournir la session au codec et au composant d'envoi.

Cette abstraction permettra d'ajouter d'autres règles, comme un routage par compte, marché, type de message ou environnement.

## Fiabilité et sémantique de livraison

JMS et FIX utilisent deux mécanismes de fiabilité indépendants :

- JMS utilise des transactions, acquittements et redélivrances ;
- FIX utilise les numéros de séquence, `PossDupFlag`, les demandes de rejeu et le `MessageStore`.

Il n'existe pas de transaction atomique naturelle couvrant simultanément JMS et FIX. La passerelle doit donc viser une livraison « au moins une fois » et rendre les traitements idempotents.

Pour un ordre, `ClOrdID` est une bonne clé métier d'idempotence. La passerelle devrait conserver une association persistante telle que :

```text
JMSMessageID → SessionID → ClOrdID → état de livraison
```

Scénario critique :

1. la passerelle lit un message JMS ;
2. QuickFIX/J persiste et envoie l'ordre ;
3. le processus tombe avant l'acquittement JMS ;
4. JMS redélivre le message ;
5. sans déduplication, l'ordre est envoyé deux fois.

Une table persistante de déduplication ou un identifiant métier stable est donc indispensable.

Pour une première version fiable :

- utiliser une session JMS transactionnelle ;
- appeler `Session.sendToTarget()` avant le commit JMS ;
- dédupliquer avec `JMSMessageID`, la session FIX et l'identifiant métier ;
- déplacer le message vers la DLQ après un nombre configurable d'échecs ;
- ne jamais rejouer automatiquement un ordre lorsque le résultat du premier envoi est ambigu.

## Comportement lorsque FIX est déconnecté

Deux politiques sont possibles.

### Mise en attente par QuickFIX/J

QuickFIX/J accepte et enregistre le message, puis tente de l'envoyer au prochain logon.

Cette solution s'appuie sur le `MessageStore`, mais les applications métier ont moins de visibilité sur les ordres en attente.

### Mise en attente dans JMS

La passerelle refuse temporairement la livraison tant que la session FIX n'est pas connectée :

```java
Session session = Session.lookupSession(sessionID);
if (session == null || !session.isLoggedOn()) {
    throw new FixSessionUnavailableException(sessionID);
}
```

Cette politique est recommandée par défaut pour les messages métier sensibles. JMS conserve alors le message et gère sa redélivrance.

La politique doit rester configurable, car certains flux peuvent accepter une mise en attente dans QuickFIX/J.

## Configuration QuickFIX/J indicative

Fichier `quickfixj.cfg` :

```ini
[default]
ConnectionType=initiator
FileStorePath=var/fix/store
FileLogPath=var/fix/log
StartTime=00:00:00
EndTime=00:00:00
HeartBtInt=30
ReconnectInterval=5
UseDataDictionary=Y

[session]
BeginString=FIX.4.4
SenderCompID=MY_BRIDGE
TargetCompID=FIX_SERVER
SocketConnectHost=fix.example.net
SocketConnectPort=9876
DataDictionary=FIX44.xml
```

## Configuration de la passerelle indicative

Fichier `bridge.properties` :

```properties
jms.connectionFactory=java:/jms/FixConnectionFactory
jms.outboundDestination=fix.outbound
jms.inboundDestination=fix.inbound
jms.eventDestination=fix.events
jms.deadLetterDestination=fix.dlq

bridge.requireFixLogon=true
bridge.inboundQueueCapacity=10000
bridge.maxDeliveryAttempts=5
bridge.messageCodec=raw-fix
```

## Gestion du cycle de vie

Le serveur doit démarrer ses composants dans l'ordre suivant :

1. charger et valider les configurations ;
2. créer la connexion et les destinations JMS ;
3. construire le `SocketInitiator` ;
4. démarrer QuickFIX/J ;
5. démarrer les producteurs JMS utilisés pour les messages entrants ;
6. démarrer le consommateur de `fix.outbound` ;
7. déclarer le service prêt.

L'arrêt doit se faire dans l'ordre inverse :

1. arrêter la consommation des nouveaux messages JMS ;
2. attendre ou abandonner proprement les traitements en cours ;
3. vider, dans une limite configurable, la file de publication entrante ;
4. arrêter le `SocketInitiator` ;
5. fermer les producteurs, sessions et connexions JMS ;
6. fermer les ressources de déduplication.

## Observabilité

La passerelle devrait publier ou exposer au minimum :

- état de chaque session FIX ;
- date du dernier logon et logout ;
- taille des files internes ;
- nombre de messages JMS consommés ;
- nombre de messages FIX envoyés et reçus ;
- nombre de redélivrances et doublons détectés ;
- nombre de messages déplacés vers la DLQ ;
- latence JMS vers FIX ;
- latence entre l'ordre et la première réponse FIX.

Les identifiants `JMSMessageID`, `JMSCorrelationID`, `ClOrdID`, `MsgSeqNum` et `SessionID` doivent être présents dans les logs structurés lorsque cela est pertinent.

## Tests nécessaires

### Tests unitaires

- conversion JMS vers FIX ;
- conversion FIX vers JMS ;
- routage vers la bonne session ;
- validation des propriétés obligatoires ;
- gestion des sessions inexistantes ou déconnectées ;
- déduplication ;
- classification des erreurs et choix entre redélivrance et DLQ.

### Tests d'intégration

- envoi d'un `NewOrderSingle` par JMS et réception côté FIX ;
- réception d'un `ExecutionReport` et publication sur `fix.inbound` ;
- arrêt et redémarrage du broker JMS ;
- perte et rétablissement de la connexion FIX ;
- redémarrage de la passerelle après l'envoi FIX mais avant le commit JMS ;
- redélivrance d'un message portant le même `JMSMessageID` ;
- saturation de la file interne ;
- arrêt propre avec des messages en cours de traitement ;
- fonctionnement avec plusieurs sessions et versions FIX.

## État et feuille de route

La première étape fonctionnelle est implémentée :

- projet Maven autonome utilisant QuickFIX/J 3.0.2 depuis Maven Central ;
- démarrage d'un `SocketInitiator` depuis `JmsFixBridgeServer` ;
- consommation de la destination sortante ;
- prise en charge du format FIX brut ;
- routage avec la propriété `fixSessionId` ;
- publication des messages reçus par `fromApp()` ;
- test d'intégration JMS et FIX du transport dans les deux directions.

Les étapes suivantes sont :

1. découpler `fromApp()` du producteur JMS avec une file interne bornée ;
2. ajouter les événements de session et une DLQ ;
3. ajouter les transactions JMS et la déduplication persistante ;
4. tester les coupures JMS, les coupures FIX, le redémarrage et la redélivrance ;
5. tester la saturation et l'arrêt avec des messages en cours ;
6. ajouter éventuellement un codec JSON métier derrière une abstraction dédiée.

## Recommandation finale

La première version devrait être un adaptateur générique utilisant des messages FIX bruts et des propriétés JMS de routage. Cette approche minimise la logique spécifique au métier, conserve l'intégralité des messages FIX et permet de valider rapidement le transport, la fiabilité et la gestion du cycle de vie.

La traduction entre des commandes JSON métier et les différentes versions FIX devrait être ajoutée ensuite derrière `FixMessageCodec`, idéalement dans un module distinct, car cette traduction dépend fortement du domaine fonctionnel et des exigences de la contrepartie FIX.
