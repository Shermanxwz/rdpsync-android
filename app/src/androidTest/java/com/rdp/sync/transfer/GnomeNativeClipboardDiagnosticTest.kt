package com.rdp.sync.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rdp.sync.ClipboardFixturePeer
import org.junit.runner.RunWith

/** Transport diagnostic only. Does not replace GnomeTransferInstrumentedTest's physical SAF gate. */
@RunWith(AndroidJUnit4::class)
class GnomeNativeClipboardDiagnosticTest : NativeClipboardDiagnosticScenario(ClipboardFixturePeer.GNOME)
