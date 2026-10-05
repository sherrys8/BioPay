/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.payment

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.kiriashi.biopay.apps.shared.PaymentMasks
import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.runtime.AppRuntime
import io.github.kiriashi.biopay.settings.ui.Theme

/**
 * The keypad stays covered until the user either verifies or asks to type the password, so the
 * payment screen is readable but its keys are unreachable. Covers use the same regions as the
 * input mask and are drawn in the window overlay; the host view hierarchy is never modified.
 */
internal object ContinuePrompt {
    private var covers: List<Cover> = emptyList()

    fun show(
        state: AppRuntime, keyboard: ViewGroup, sessionId: Long,
        onContinue: () -> Unit, onManualEntry: () -> Unit
    ): Boolean {
        // A callback replayed by an older attempt must not replace the current session's cover.
        if (covers.maxOfOrNull { it.sessionId }?.let { it > sessionId } == true) return false
        dismiss()
        val root = keyboard.rootView
        covers = regions(state, keyboard).map { (host, targets) ->
            Cover(state, host, keyboard, sessionId, targets, host === root, onContinue, onManualEntry)
        }
        covers.forEach(Cover::attach)
        ModuleLog.d { "keypad cover shown: app=${state.adapter.app}, session=$sessionId, hosts=${covers.size}" }
        return covers.isNotEmpty()
    }

    /** Mirrors a tap on 继续验证; used by the volume key shortcut. */
    fun triggerNow(): Boolean = covers.firstOrNull { it.hasEntries }?.fireContinue() == true

    fun dismiss() {
        covers.forEach(Cover::close)
        covers = emptyList()
    }

    fun blocksTouch(root: View, event: MotionEvent): Boolean =
        covers.any { it.touches(root, event) }

    private fun regions(state: AppRuntime, keyboard: ViewGroup): List<Pair<ViewGroup, List<View>>> {
        val session = state.session
        val keys = state.adapter.digitKeys(keyboard)?.filterNotNull()
        val regions = PaymentMasks.resolve(
            state.adapter.app, keyboard, session.getInputEditText(), session.getConfirmButton(), keys
        ).map { it.host to it.targets }.toMutableList()
        val window = keyboard.rootView as? ViewGroup ?: return regions
        val index = regions.indexOfFirst { it.first === window }
        if (index < 0) {
            regions += window to listOf<View>(keyboard)
        } else if (!keypadCovered(regions[index].second, keyboard)) {
            // PaymentMasks resolves the area that shows the plaintext, which is not the area that
            // can be tapped: a keypad that fails to resolve, or that resolves to the window itself
            // and is then dropped, leaves the keys live under a cover over the hint row only.
            regions[index] = window to (regions[index].second + keyboard)
        }
        return regions
    }

    private fun keypadCovered(targets: List<View>, keyboard: ViewGroup): Boolean {
        val keypad = Rect()
        if (!keyboard.getGlobalVisibleRect(keypad)) return false
        val covered = Rect()
        for (view in targets) {
            val bounds = Rect()
            if (view.getGlobalVisibleRect(bounds)) covered.union(bounds)
        }
        return covered.contains(keypad)
    }

    private class Cover(
        private val state: AppRuntime,
        private val host: ViewGroup,
        private val keyboard: ViewGroup,
        val sessionId: Long,
        private val targets: List<View>,
        val hasEntries: Boolean,
        private val onContinue: () -> Unit,
        private val onManualEntry: () -> Unit
    ) : Drawable(), ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

        private val colors = Theme.colors(host.context, state.adapter.app)
        private val density = host.context.resources.displayMetrics.density
        private val surface = fillPaint(colors.surface)
        private val continuePaint = fillPaint(colors.primary)
        private val manualPaint = fillPaint(colors.surfaceContainerHighest)
        private val continueText = textPaint(16f, colors.onPrimary)
        private val manualText = textPaint(14f, colors.onSurfaceVariant)
        private val panel = RectF()
        private val continueButton = RectF()
        private val manualButton = RectF()
        private val drawnPanel = RectF()
        private val drawnContinue = RectF()
        private val drawnManual = RectF()
        private val bounds = Rect()
        private val location = IntArray(2)
        private val observer = host.viewTreeObserver
        private val continueHeight = 48f * density
        private val continueRadius = continueHeight / 2f
        private val continueGap = 14f * density
        private val manualHeight = 36f * density
        private val manualRadius = manualHeight / 2f
        private val panelMargin = 24f * density
        private var closed = false
        private var gesture = false

        private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }

        private fun textPaint(sizeSp: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sizeSp, host.resources.displayMetrics
            )
        }

        fun attach() {
            host.overlay.add(this)
            observer.addOnPreDrawListener(this)
            host.addOnAttachStateChangeListener(this)
            keyboard.addOnAttachStateChangeListener(this)
        }

        fun fireContinue(): Boolean = fire(CONTINUE_LABEL, onContinue)

        private fun fire(label: String, action: () -> Unit): Boolean {
            if (closed || !isCurrent()) return false
            close()
            ModuleLog.d { "keypad cover dismissed: $label, session=$sessionId" }
            action()
            return true
        }

        fun close() {
            if (closed) return
            closed = true
            gesture = false
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            host.overlay.remove(this)
            host.removeOnAttachStateChangeListener(this)
            keyboard.removeOnAttachStateChangeListener(this)
            panel.setEmpty()
        }

        /** The whole cover consumes its own gesture so no hidden key can receive a touch. */
        fun touches(root: View, event: MotionEvent): Boolean {
            if (closed || root !== host.rootView || !isCurrent()) return false
            if (panel.isEmpty) return false
            host.getLocationOnScreen(location)
            val x = event.rawX - location[0]
            val y = event.rawY - location[1]
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN) gesture = panel.contains(x, y)
            val consume = gesture
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                val wasGesturing = gesture
                gesture = false
                if (wasGesturing && action == MotionEvent.ACTION_UP) tapAt(x, y)
            }
            return consume
        }

        private fun tapAt(x: Float, y: Float) {
            if (!hasEntries) return
            when {
                continueButton.contains(x, y) -> fire(CONTINUE_LABEL, onContinue)
                manualButton.contains(x, y) -> fire(MANUAL_LABEL, onManualEntry)
            }
        }

        override fun onPreDraw(): Boolean {
            if (closed) return true
            if (!isCurrent() || !host.isAttachedToWindow || !keyboard.isAttachedToWindow) {
                close()
                return true
            }
            updateBounds()
            if (drawnPanel != panel || drawnContinue != continueButton || drawnManual != manualButton) {
                drawnPanel.set(panel)
                drawnContinue.set(continueButton)
                drawnManual.set(manualButton)
                invalidateSelf()
            }
            return true
        }

        private fun isCurrent(): Boolean = !state.isClosed &&
            state.session.isCurrentSession(sessionId) &&
            state.session.getCurrentKeyboardView() === keyboard

        private fun updateBounds() {
            setBounds(0, 0, host.width, host.height)
            host.getLocationOnScreen(location)
            panel.setEmpty()
            for (view in targets) {
                if (!view.isAttachedToWindow || !view.getGlobalVisibleRect(bounds)) continue
                panel.union((bounds.left - location[0]).toFloat(), (bounds.top - location[1]).toFloat(),
                    (bounds.right - location[0]).toFloat(), (bounds.bottom - location[1]).toFloat())
            }
            continueButton.setEmpty()
            manualButton.setEmpty()
            if (!hasEntries) return
            // Too short for the exits means a bar that swallows touches and offers nothing, so it
            // uncovers instead. Hosts with hasEntries=false are secondary windows and still cover.
            if (panel.height() < continueHeight + manualHeight + continueGap + panelMargin) {
                panel.setEmpty()
                return
            }
            val width = (panel.width() - panelMargin * 2f).coerceAtMost(320f * density)
            if (width <= 0f) {
                panel.setEmpty()
                return
            }
            val left = panel.centerX() - width / 2f
            val top = panel.centerY() -
                (continueHeight + continueGap + manualHeight) / 2f
            continueButton.set(left, top, left + width, top + continueHeight)
            val manualWidth = (manualText.measureText(MANUAL_LABEL) + 32f * density).coerceAtMost(width)
            manualButton.set(panel.centerX() - manualWidth / 2f, top + continueHeight + continueGap,
                panel.centerX() + manualWidth / 2f, top + continueHeight + continueGap + manualHeight)
        }

        override fun draw(canvas: Canvas) {
            if (panel.isEmpty) return
            canvas.drawRect(panel, surface)
            if (!hasEntries) return
            if (!continueButton.isEmpty) drawPill(canvas, continueButton, CONTINUE_LABEL, continuePaint,
                continueText, continueRadius)
            if (!manualButton.isEmpty) drawPill(canvas, manualButton, MANUAL_LABEL, manualPaint,
                manualText, manualRadius)
        }

        private fun drawPill(
            canvas: Canvas, box: RectF, label: String, background: Paint, text: Paint, radius: Float
        ) {
            canvas.drawRoundRect(box, radius, radius, background)
            canvas.drawText(label, box.centerX(),
                (box.top + box.bottom) / 2f - (text.ascent() + text.descent()) / 2f, text)
        }

        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) = close()

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Required by Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private companion object {
            const val CONTINUE_LABEL = "继续验证"
            const val MANUAL_LABEL = "输入密码"
        }
    }
}
