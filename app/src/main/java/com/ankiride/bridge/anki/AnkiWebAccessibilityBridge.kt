package com.ankiride.bridge.anki

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.ankiride.bridge.core.AnkiAction
import com.ankiride.bridge.core.DiagnosticLogger
import com.ankiride.bridge.core.LogCategory

/**
 * On-demand accessibility bridge for AnkiWeb (ankiweb.net) in mobile browsers.
 * Never listens passively to events and ignores password/sensitive fields.
 */
class AnkiWebAccessibilityBridge {

    companion object {
        const val FLIP_TO_GRADE_GUARD_MS = 350L
        private const val MAX_BUTTON_LABEL_LENGTH = 32

        val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "com.sec.android.app.sbrowser",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.vivaldi.browser",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.duckduckgo.mobile.android",
            "com.kiwibrowser.browser",
            "org.chromium.chrome",
            "app.vanadium.browser"
        )

        private val SHOW_ANSWER_LABELS = listOf("Show Answer", "Show answer")
        private val AGAIN_LABELS = listOf("Again")
        private val HARD_LABELS = listOf("Hard", "Again")
        private val GOOD_LABELS = listOf("Good")
        private val EASY_LABELS = listOf("Easy", "Good")
        private val ANKIWEB_MARKERS = listOf("ankiweb.net", "ankiuser.net", "ankiweb")

        fun isAnkiWebMarkerText(rawText: String?): Boolean {
            if (rawText.isNullOrBlank()) return false
            val lower = rawText.lowercase()
            return ANKIWEB_MARKERS.any { lower.contains(it) }
        }

        /**
         * Matches AnkiWeb button labels (e.g., "Show Answer", "Again", "Good 10m", "1d\nGood")
         * while rejecting long card sentences that happen to contain those words.
         */
        fun matchesButtonLabel(rawText: String?, targets: List<String>): Boolean {
            if (rawText.isNullOrBlank()) return false
            val collapsed = rawText.replace(Regex("\\s+"), " ").trim()
            if (collapsed.isEmpty() || collapsed.length > MAX_BUTTON_LABEL_LENGTH) return false
            return targets.any { target ->
                collapsed.equals(target, ignoreCase = true) ||
                    collapsed.startsWith("$target ", ignoreCase = true) ||
                    collapsed.endsWith(" $target", ignoreCase = true)
            }
        }
    }

    @Volatile
    var lastFlipTimestampMs: Long = 0L

    fun performAnkiWebAction(
        service: AccessibilityService,
        action: AnkiAction,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (action == AnkiAction.UNDO) {
            DiagnosticLogger.warn(LogCategory.ANKI, "AnkiWeb does not support Undo")
            return false
        }

        val browserWindow: AccessibilityWindowInfo? = service.windows
            ?.firstOrNull { win ->
                val pkg = win.root?.packageName?.toString()
                pkg != null && pkg in BROWSER_PACKAGES
            }

        val browserRoot: AccessibilityNodeInfo? = browserWindow?.root
            ?: service.rootInActiveWindow?.takeIf { it.packageName?.toString() in BROWSER_PACKAGES }

        if (browserRoot == null) {
            DiagnosticLogger.warn(LogCategory.ANKI, "Cannot dispatch AnkiWeb $action: no supported browser visible on screen")
            return false
        }

        if (!containsAnkiWebMarker(browserRoot)) {
            DiagnosticLogger.warn(LogCategory.ANKI, "AnkiWeb page not detected in browser; open ankiweb.net before grading")
            return false
        }

        if (findAndClickWebButton(browserRoot, action, nowMs)) {
            return true
        }

        DiagnosticLogger.warn(LogCategory.ANKI, "AnkiWeb action $action could not find matching button")
        return false
    }

    fun containsAnkiWebMarker(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null || depth > 24 || node.isPassword || !node.isVisibleToUser) return false
        if (isAnkiWebMarkerText(node.text?.toString()) ||
            isAnkiWebMarkerText(node.contentDescription?.toString()) ||
            isAnkiWebMarkerText(node.viewIdResourceName)
        ) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (containsAnkiWebMarker(node.getChild(i), depth + 1)) return true
        }
        return false
    }

    fun findAndClickWebButton(
        root: AccessibilityNodeInfo?,
        action: AnkiAction,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (root == null || root.packageName?.toString() !in BROWSER_PACKAGES) return false
        if (root.isPassword || !containsAnkiWebMarker(root)) return false

        return when (action) {
            AnkiAction.UNDO -> false
            AnkiAction.FLIP_CARD -> {
                val flipped = clickMatchingButton(root, SHOW_ANSWER_LABELS, action)
                if (flipped) lastFlipTimestampMs = nowMs
                flipped
            }
            AnkiAction.FLIP_OR_AGAIN -> clickShowAnswerOrEase(root, AGAIN_LABELS, action, nowMs)
            AnkiAction.FLIP_OR_HARD -> clickShowAnswerOrEase(root, HARD_LABELS, action, nowMs)
            AnkiAction.FLIP_OR_GOOD -> clickShowAnswerOrEase(root, GOOD_LABELS, action, nowMs)
            AnkiAction.FLIP_OR_EASY -> clickShowAnswerOrEase(root, EASY_LABELS, action, nowMs)
        }
    }

    private fun clickShowAnswerOrEase(
        root: AccessibilityNodeInfo,
        easeLabels: List<String>,
        action: AnkiAction,
        nowMs: Long
    ): Boolean {
        if (clickMatchingButton(root, SHOW_ANSWER_LABELS, AnkiAction.FLIP_CARD)) {
            lastFlipTimestampMs = nowMs
            DiagnosticLogger.info(LogCategory.ANKI, "AnkiWeb: Flipped card via Show Answer (armed 350ms bounce guard)")
            return true
        }
        if ((nowMs - lastFlipTimestampMs) < FLIP_TO_GRADE_GUARD_MS) {
            DiagnosticLogger.warn(LogCategory.ANKI, "AnkiWeb Bounce Guard (350ms) blocked double-press for $action")
            return false
        }
        return clickMatchingButton(root, easeLabels, action)
    }

    private fun clickMatchingButton(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        action: AnkiAction
    ): Boolean {
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectButtonNodes(root, candidates, depth = 0)
        for (node in candidates) {
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            if (matchesButtonLabel(text, labels) || matchesButtonLabel(desc, labels)) {
                if (clickNodeOrParent(node)) {
                    DiagnosticLogger.info(LogCategory.ANKI, "AnkiWeb clicked button '${text ?: desc}' for $action")
                    return true
                }
            }
        }
        return false
    }

    private fun collectButtonNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 24 || node.isPassword || !node.isVisibleToUser) return
        val cls = node.className?.toString().orEmpty()
        val looksInteractive = node.isClickable ||
            cls.endsWith("Button", ignoreCase = true) ||
            cls.contains("Button", ignoreCase = true)
        if (looksInteractive && (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectButtonNodes(child, out, depth + 1)
        }
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isPassword) return false
            if (current.isClickable && current.isEnabled) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }
}
