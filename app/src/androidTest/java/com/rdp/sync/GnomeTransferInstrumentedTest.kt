package com.rdp.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Real GNOME Wayland, NLA/software Progressive and native CLIPRDR/FUSE peer. */
@RunWith(AndroidJUnit4::class)
class GnomeTransferInstrumentedTest : ClipboardTransferScenario(ClipboardFixturePeer.GNOME)
