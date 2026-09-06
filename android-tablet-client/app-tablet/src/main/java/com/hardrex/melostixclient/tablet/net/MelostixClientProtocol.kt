package com.hardrex.melostixclient.tablet.net

/** Protocol constants towards the app-master: must stay manually in sync with
 *  com.hardrex.melostix.net.MelostixClientProtocol on the app-master side (no shared Gradle
 *  module between the two, given the very different minSdk). */
object MelostixClientProtocol {
    const val DISCOVERY_PORT = 8421
    const val DATA_PORT = 8420
    const val SERVICE_NAME = "melostixservice"

    /** Master-slave contract version (MelostixProtocol) spoken by this client. 1.2.0: in
     *  addition to the optional authentication handshake (1.1.0, see MelostixClient.readFrom),
     *  it now also sends the optional identification clientHello (see CLIENT_TYPE below and
     *  MelostixClient.sendClientHello) - a master that doesn't read it or doesn't support it
     *  behaves exactly as before, no change. */
    const val PROTOCOL_VERSION = "1.2.0"

    /** `clientType` sent in the `clientHello` (protocol 1.2.0): a stable, namespaced identifier
     *  of THIS implementation, not of the individual device - see protocol.md, "Client
     *  identification (optional)", "Registered clientType values" table in MelostixProtocol. */
    const val CLIENT_TYPE = "melostix.android-tablet"
}
