package app.template.patches.instagram.reels

import app.morphe.patcher.Fingerprint

internal object InstagramAppShellOnCreateFingerprint : Fingerprint(
    name = "onCreate",
    custom = { _, classDef ->
        classDef.endsWith("/InstagramAppShell;")
    },
)
