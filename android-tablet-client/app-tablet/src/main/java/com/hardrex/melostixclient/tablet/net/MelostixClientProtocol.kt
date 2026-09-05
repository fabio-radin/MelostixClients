package com.hardrex.melostixclient.tablet.net

/** Costanti del protocollo verso l'app-master: devono restare allineate a mano con
 *  com.hardrex.melostix.net.MelostixClientProtocol lato app-master (nessun modulo Gradle
 *  condiviso tra i due, dato il minSdk molto diverso). */
object MelostixClientProtocol {
    const val DISCOVERY_PORT = 8421
    const val DATA_PORT = 8420
    const val SERVICE_NAME = "melostixservice"

    /** Versione del contratto master-slave (MelostixProtocol) parlata da questo client. 1.2.0:
     *  in aggiunta all'handshake di autenticazione opzionale (1.1.0, vedi MelostixClient.readFrom),
     *  invia ora anche il clientHello opzionale di identificazione (vedi CLIENT_TYPE sotto e
     *  MelostixClient.sendClientHello) - un master che non lo legge o non lo supporta si comporta
     *  esattamente come prima, nessun cambiamento. */
    const val PROTOCOL_VERSION = "1.2.0"

    /** `clientType` inviato nel `clientHello` (protocollo 1.2.0): identificatore stabile e
     *  namespaced di QUESTA implementazione, non del singolo dispositivo - vedi protocol.md,
     *  "Client identification (optional)", tabella "Registered clientType values" in
     *  MelostixProtocol. */
    const val CLIENT_TYPE = "melostix.android-tablet"
}
