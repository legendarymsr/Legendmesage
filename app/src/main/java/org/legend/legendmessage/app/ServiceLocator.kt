package org.legend.legendmessage.app

import android.content.Context
import org.legend.legendmessage.crypto.IdentityManager
import org.legend.legendmessage.crypto.SecretStore

/**
 * Dead-simple manual dependency wiring. One instance per process, created by
 * [App]. Avoids a DI framework for what is, so far, a handful of singletons.
 */
class ServiceLocator(context: Context) {
    val appContext: Context = context.applicationContext
    val secretStore: SecretStore by lazy { SecretStore(appContext) }
    val identity: IdentityManager by lazy { IdentityManager(appContext, secretStore) }
}
