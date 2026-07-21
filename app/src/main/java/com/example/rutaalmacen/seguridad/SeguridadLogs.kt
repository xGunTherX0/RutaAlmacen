package com.example.rutaalmacen.seguridad

import android.util.Log
import com.example.rutaalmacen.BuildConfig

object SeguridadLogs {

    private const val TAG = "SeguridadLogs"

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, msg)
        }
    }

    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.i(tag, msg)
        }
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
    }
}
