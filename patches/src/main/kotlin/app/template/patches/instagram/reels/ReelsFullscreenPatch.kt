package app.template.patches.instagram.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val EXTENSION_CLASS = "Lapp/template/extension/extension/ReelsFullscreenPatch;"

@Suppress("unused")
val reelsFullscreenPatch = bytecodePatch(
    name = "Reels fullscreen",
    description = "Extends Reels behind the status bar and to the bottom of the screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith("extensions/extension.mpe")

    execute {
        // The extension only needs the application to watch the view hierarchy of its activities.
        InstagramAppShellOnCreateFingerprint.method.apply {
            val invokeSuperIndex = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_SUPER || it.opcode == Opcode.INVOKE_SUPER_RANGE
            }
            val contextRegister = when (val invokeSuper = instructions[invokeSuperIndex]) {
                is RegisterRangeInstruction -> invokeSuper.startRegister
                else -> (invokeSuper as FiveRegisterInstruction).registerC
            }

            addInstruction(
                invokeSuperIndex + 1,
                "invoke-static/range { v$contextRegister .. v$contextRegister }, " +
                    "$EXTENSION_CLASS->init(Landroid/content/Context;)V",
            )
        }
    }
}

@Suppress("unused")
val hideTabBarInReelsPatch = bytecodePatch(
    name = "Hide tab bar in Reels",
    description = "Hides the bottom tab bar while Reels is on screen. Use the back gesture to leave Reels.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(reelsFullscreenPatch)

    execute {
        InstagramAppShellOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $EXTENSION_CLASS->enableHideTabBar()V",
        )
    }
}

@Suppress("unused")
val expandPhotosInReelsPatch = bytecodePatch(
    name = "Expand photos in Reels",
    description = "Enlarges photos and wide videos in Reels so they use the empty space around them.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(reelsFullscreenPatch)

    execute {
        InstagramAppShellOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $EXTENSION_CLASS->enableExpandMedia()V",
        )
    }
}

@Suppress("unused")
val moveReelsButtonsPatch = bytecodePatch(
    name = "Move Reels buttons to the edge",
    description = "Moves the like, comment and share buttons in Reels to the right edge of the screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(reelsFullscreenPatch)

    execute {
        InstagramAppShellOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $EXTENSION_CLASS->enableMoveButtons()V",
        )
    }
}

@Suppress("unused")
val reelsProgressBarOnRailPatch = bytecodePatch(
    name = "Reels progress bar on the navigation rail",
    description = "Shows the progress bar of Reels as a vertical bar between the navigation rail " +
        "and the reel on large screens.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(reelsFullscreenPatch)

    execute {
        InstagramAppShellOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $EXTENSION_CLASS->enableSeamScrubber()V",
        )
    }
}

@Suppress("unused")
val restartButtonOnRailPatch = bytecodePatch(
    name = "Restart button on the navigation rail",
    description = "Adds a button that restarts Instagram to the bottom of the navigation rail on large screens.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(reelsFullscreenPatch)

    execute {
        InstagramAppShellOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $EXTENSION_CLASS->enableRestartButton()V",
        )
    }
}

@Suppress("unused")
val reelsFullscreenDebugPatch = bytecodePatch(
    name = "Reels fullscreen debug",
    description = "Copies a description of the layout of each reel to the clipboard a few seconds " +
        "after it is shown. Only needed to report layout problems.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(reelsFullscreenPatch)

    execute {
        InstagramAppShellOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $EXTENSION_CLASS->enableDebug()V",
        )
    }
}
