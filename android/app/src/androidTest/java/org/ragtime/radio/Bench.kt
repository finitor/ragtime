package org.ragtime.radio

import androidx.test.platform.app.InstrumentationRegistry

/**
 * The fake-host bench's ports as this device reaches them. On a phone whose
 * own Data2G TNC holds 8100/8300, tools/android-smoke.sh forwards the bench
 * from ports moved up by portOffset, so a test never meets the real modem.
 */
internal fun benchPort(port:Int)=port+(InstrumentationRegistry.getArguments().getString("portOffset")?.toInt() ?: 0)
