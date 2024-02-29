package org.amnezia.vpn.protocol

import android.os.Bundle

private const val STATE_KEY = "state"
private const val LAST_CONFIG_NAME_KEY = "lastConfigName"

@Suppress("DataClassPrivateConstructor")
data class Status private constructor(
    val state: ProtocolState,
    val lastConfigName: String?
) {
    private constructor(builder: Builder) : this(builder.state, builder.lastConfigName)

    class Builder {
        lateinit var state: ProtocolState
        var lastConfigName: String? = null
            private set

        fun setState(state: ProtocolState) = apply { this.state = state }

        fun setLastConfigName(lastConfigName: String?) = apply { this.lastConfigName = lastConfigName }

        fun build(): Status = Status(this)
    }

    companion object {
        inline fun build(block: Builder.() -> Unit): Status = Builder().apply(block).build()
    }
}

fun Bundle.putStatus(status: Status) {
    putInt(STATE_KEY, status.state.ordinal)
    putString(LAST_CONFIG_NAME_KEY, status.lastConfigName)
}

fun Bundle.getStatus(): Status =
    Status.build {
        setState(ProtocolState.entries[getInt(STATE_KEY)])
        setLastConfigName(getString(LAST_CONFIG_NAME_KEY))
    }
