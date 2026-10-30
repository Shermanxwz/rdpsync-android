package com.rdp.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Second native CLIPRDR/FUSE peer; not a Windows OS or Windows NLA claim. */
@RunWith(AndroidJUnit4::class)
class XrdpTransferInstrumentedTest : ClipboardTransferScenario(ClipboardFixturePeer.XRDP)
