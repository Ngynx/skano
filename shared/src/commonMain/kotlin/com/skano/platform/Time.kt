package com.skano.platform

/** Epoch milliseconds on the current platform. Injectable clock seam for tests. */
expect fun currentTimeMillis(): Long
