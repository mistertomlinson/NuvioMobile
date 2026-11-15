package com.nuvio.app.features.watchprogress

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual object WatchProgressClock {
    actual fun nowEpochMs(): Long = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
}
