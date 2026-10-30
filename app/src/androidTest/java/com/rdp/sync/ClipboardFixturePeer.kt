package com.rdp.sync

/** Fixed isolated runner endpoints only; no arbitrary host/credential input. */
enum class ClipboardFixturePeer(
    val enabledArgument: String,
    val fingerprintArgument: String,
    val rdpPort: Int,
    val statePort: Int,
    val transferPort: Int,
    val evidenceName: String,
    val fuseEvidenceField: String,
    val deviceId: Long,
    val scope: String,
) {
    GNOME("gnomeFixture", "gnomeFixtureFingerprint", 33891, 8768, 8768,
        "gnome-transfer-evidence", "via_gnome_fuse", 910002L,
        "GNOME46 per-user Wayland NLA/software GFX9 and native CLIPRDR/FUSE; not GDM or Windows OS"),
    XRDP("xrdpFileFixture", "fixtureFingerprint", 3389, 8765, 8769,
        "xrdp-transfer-evidence", "via_xrdp_fuse", 920002L,
        "Pinned xrdp X11 native CLIPRDR/FUSE and classic graphics; not Windows OS/NLA; original fourteen-test scenario separate"),
}
