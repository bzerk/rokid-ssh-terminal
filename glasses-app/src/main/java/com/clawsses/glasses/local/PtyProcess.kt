package com.clawsses.glasses.local

import android.os.ParcelFileDescriptor

object PtyProcess {
    init { System.loadLibrary("pty_bridge") }

    @JvmStatic private external fun nativeCreate(cols: Int, rows: Int, env: Array<String>): Int
    @JvmStatic private external fun nativeResize(fd: Int, cols: Int, rows: Int)

    fun create(cols: Int, rows: Int, env: Array<String>): ParcelFileDescriptor? {
        val fd = nativeCreate(cols, rows, env)
        if (fd < 0) return null
        return ParcelFileDescriptor.adoptFd(fd)
    }

    fun resize(pfd: ParcelFileDescriptor, cols: Int, rows: Int) {
        nativeResize(pfd.fd, cols, rows)
    }
}
