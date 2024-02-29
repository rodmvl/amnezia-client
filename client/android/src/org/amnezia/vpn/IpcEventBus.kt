package org.amnezia.vpn

import android.os.DeadObjectException
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import org.amnezia.vpn.util.Log

private const val TAG = "IpcEventBus"

class IpcEventBus(
    private val onDeadObjectException: () -> Unit = {},
    private val onRemoteException: () -> Unit = {},
    private val messengerName: String = "Unknown"
) {
    private var clients: HashSet<Messenger> = HashSet()

    fun add(messenger: Messenger) {
        this.clients.add(messenger)
    }

    fun reset() {
        clients.clear()
    }

    fun send(msg: () -> Message) = clients.forEach { it.sendMsg(msg()) }

    fun <T> send(msg: T)
            where T : Enum<T>, T : IpcMessage = clients.forEach { it.sendMsg(msg.packToMessage()) }

    private fun Messenger.sendMsg(msg: Message) {
        try {
            send(msg)
        } catch (e: DeadObjectException) {
            Log.w(TAG, "One of client in $messengerName eventBus is dead")
            clients.remove(this)
            onDeadObjectException()
        } catch (e: RemoteException) {
            Log.w(TAG, "Sending a message to the $messengerName messenger failed: ${e.message}")
            onRemoteException()
        }
    }
}