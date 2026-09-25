package com.vitacut.core.common.logging

import android.util.Log

/**
 * Minimal logging facade. Release builds only emit warnings and errors; verbose media pipeline
 * logs stay available in debug builds where they matter.
 */
object VitaLog {

    var debugBuild: Boolean = false

    fun v(tag: String, message: String) {
        if (debugBuild) Log.v(tag, message)
    }

    fun d(tag: String, message: String) {
        if (debugBuild) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
}
