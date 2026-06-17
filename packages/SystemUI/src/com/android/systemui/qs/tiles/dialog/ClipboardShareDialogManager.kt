package com.android.systemui.qs.tiles.dialog

import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import javax.inject.Inject
import android.content.Context
import com.android.systemui.statusbar.phone.SystemUIDialog
import kotlinx.coroutines.CoroutineScope

private const val INTERACTION_JANK_TAG = "clipboard_share"

@SysUISingleton
class ClipboardShareDialogManager @Inject constructor(
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val dialogDelegateFactory: ClipboardShareDialogDelegate.Factory,
) {
    private var dialog: SystemUIDialog? = null

    fun create(context: Context, expandable: Expandable?) {
        if (dialog != null) return

        try {
            val newDialog = dialogDelegateFactory.create(context).createDialog()
            dialog = newDialog

            val controller = expandable?.dialogTransitionController(
                DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, INTERACTION_JANK_TAG)
            )

            if (controller != null) {
                dialogTransitionAnimator.show(
                    newDialog,
                    controller,
                    animateBackgroundBoundsChange = true,
                )
            } else {
                newDialog.show()
            }
        } catch (e: Exception) {
            // Do not leave a non-null dialog that was never shown
            dialog = null
            android.util.Log.e(
                "ClipboardShareDialog", "Failed to create/show clipboard share dialog", e)
        }
    }

    fun destroyDialog() {
        dialog = null
    }
}