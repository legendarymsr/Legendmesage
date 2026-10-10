package org.legend.legendmessage.app

import android.content.Context
import org.legend.legendmessage.crypto.BackupManager
import org.legend.legendmessage.crypto.CryptoEngine
import org.legend.legendmessage.crypto.ClientAuthKeys
import org.legend.legendmessage.crypto.IdentityManager
import org.legend.legendmessage.crypto.LockManager
import org.legend.legendmessage.crypto.SecretStore
import org.legend.legendmessage.crypto.SignalStore
import org.legend.legendmessage.data.ContactStore
import org.legend.legendmessage.data.MailboxStore
import org.legend.legendmessage.data.MessageDb
import org.legend.legendmessage.data.MessageStore
import org.legend.legendmessage.net.InboundDelivery
import org.legend.legendmessage.net.MailboxPoller
import org.legend.legendmessage.net.MessageSender
import org.legend.legendmessage.net.PeerServer

/**
 * Dead-simple manual dependency wiring. One instance per process, created by
 * [App]. Avoids a DI framework for what is, so far, a handful of singletons.
 */
class ServiceLocator(context: Context) {
    val appContext: Context = context.applicationContext
    val secretStore: SecretStore by lazy { SecretStore(appContext) }
    val identity: IdentityManager by lazy { IdentityManager(appContext, secretStore) }
    val contacts: ContactStore by lazy { ContactStore(secretStore) }
    val signalStore: SignalStore by lazy { SignalStore(secretStore, identity) }
    val crypto: CryptoEngine by lazy { CryptoEngine(identity, signalStore, contacts) }
    val backup: BackupManager by lazy { BackupManager(secretStore, identity) }
    val lock: LockManager by lazy { LockManager(secretStore) }
    val clientAuth: ClientAuthKeys by lazy { ClientAuthKeys(secretStore) }

    val messageDb: MessageDb by lazy { MessageDb(appContext, secretStore) }
    val messages: MessageStore by lazy { MessageStore(messageDb) }
    val mailboxStore: MailboxStore by lazy { MailboxStore(messageDb) }

    val inbound: InboundDelivery by lazy { InboundDelivery(contacts, messages, crypto) }
    val sender: MessageSender by lazy { MessageSender(contacts, messages, crypto) }
    val peerServer: PeerServer by lazy { PeerServer(identity, inbound, mailboxStore) { crypto.myKyberBundle() } }
    val mailboxPoller: MailboxPoller by lazy { MailboxPoller(identity, inbound) }
}
