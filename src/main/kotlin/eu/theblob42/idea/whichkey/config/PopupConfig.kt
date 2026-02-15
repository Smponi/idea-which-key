package eu.theblob42.idea.whichkey.config

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import eu.theblob42.idea.whichkey.model.Mapping
import kotlinx.coroutines.*
import java.awt.Point
import javax.swing.JEditorPane
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.ceil

object PopupConfig {

    private const val DEFAULT_POPUP_DELAY = 200
    private val defaultPopupDelay: Int
    get() = when (val delay = injector.variableService.getGlobalVariableValue("WhichKey_DefaultDelay")) {
        null -> DEFAULT_POPUP_DELAY
        !is VimInt -> DEFAULT_POPUP_DELAY
        else -> delay.value
    }

    private val DEFAULT_SORT_OPTION = SortOption.BY_KEY
    private val sortOption: SortOption
    get() = when (val option = injector.variableService.getGlobalVariableValue("WhichKey_SortOrder")) {
        null -> DEFAULT_SORT_OPTION
        !is VimString -> DEFAULT_SORT_OPTION
        else -> SortOption.values().firstOrNull { it.name.equals(option.asString(), ignoreCase = true) } ?: DEFAULT_SORT_OPTION
    }

    private const val DEFAULT_SORT_CASE_SENSITIVE = true
    private val sortCaseSensitive: Boolean
    get() = when (val option = injector.variableService.getGlobalVariableValue("WhichKey_SortCaseSensitive")) {
        null -> DEFAULT_SORT_CASE_SENSITIVE
        !is VimString -> DEFAULT_SORT_CASE_SENSITIVE
        else -> option.asString().toBoolean()
    }

    // configurable popup position within the IDE frame (center, top, bottom)
    private const val POSITION_KEY = "WhichKey_Position"
    private val DEFAULT_POSITION = PopupPosition.BOTTOM
    private val popupPosition: PopupPosition
    get() = when (val pos = injector.variableService.getGlobalVariableValue(POSITION_KEY)) {
        null -> DEFAULT_POSITION
        !is VimString -> DEFAULT_POSITION
        else -> PopupPosition.values().firstOrNull { it.name.equals(pos.asString(), ignoreCase = true) } ?: DEFAULT_POSITION
    }

    // whether the popup should be dismissed when clicking outside of it
    private const val CANCEL_ON_CLICK_OUTSIDE_KEY = "WhichKey_CancelOnClickOutside"
    private val cancelOnClickOutside: Boolean
    get() = when (val option = injector.variableService.getGlobalVariableValue(CANCEL_ON_CLICK_OUTSIDE_KEY)) {
        null -> true
        !is VimString -> true
        else -> option.asString().toBoolean()
    }

    // the currently displayed popup, null if no popup is visible
    private var currentPopup: JBPopup? = null
    // coroutine job for the delayed popup display, allows cancellation before the popup is shown
    private var displayPopupJob: Job? = null
    // timer for auto-hiding the popup after the configured timeout (replaces Balloon's built-in fadeout)
    private var fadeoutTimer: Timer? = null

    /**
     * Either cancel the display job or hide the current popup
     */
    fun hidePopup() {
        // cancel job or wait till it's done (if it already started)
        runBlocking {
            displayPopupJob?.cancelAndJoin()
        }
        // cancel fadeout timer if present
        fadeoutTimer?.stop()
        fadeoutTimer = null
        // hide popup if present and reset value
        currentPopup?.let {
            it.cancel()
            currentPopup = null
        }
    }

    /**
     * Show the popup presenting the nested mappings for [typedKeys]
     * Do not show the popup instantly but instead start a coroutine job to show the popup after a delay
     *
     * If there are no 'nestedMappings' (empty list) this function does nothing
     *
     * @param ideFrame The [JFrame] to attach the popup to
     * @param typedKeys The already typed key stroke sequence
     * @param nestedMappings A [List] of nested mappings to display
     * @param startTime Timestamp to consider for the calculation of the popup delay
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun showPopup(ideFrame: JFrame, typedKeys: List<KeyStroke>, nestedMappings: List<Pair<String, Mapping>>, startTime: Long) {
        if (nestedMappings.isEmpty()) {
            return
        }

        /*
         * the factor 0.65 was found by experimenting and comparing result lengths pixel by pixel
         * it might be erroneous and could change in the future
         */
        val frameWidth = (ideFrame.width * 0.65).toInt()
        // check for the longest string as this will most probably be the widest mapping
        val maxMapping = nestedMappings.maxByOrNull { (key, mapping) -> key.length + mapping.description.length }!! // (we have manually checked that 'nestedMappings' is not empty)
        // calculate the pixel width of the longest mapping string (with HTML formatting & styling)
        val maxStringWidth = JLabel("<html>${FormatConfig.formatMappingEntry(maxMapping)}</html>").preferredSize.width
        val possibleColumns = (frameWidth / maxStringWidth).let {
            when {
                // ensure a minimum value of 1 to avoid dividing by zero
                it < 1 -> 1
                // always use the full available screen space
                it > nestedMappings.size -> nestedMappings.size
                else -> it
            }
        }
        // use as much space for every column as possible
        val columnWidth = frameWidth / possibleColumns

        val elementsPerColumn = ceil(nestedMappings.size / possibleColumns.toDouble()).toInt()
        val windowedMappings = sortMappings(nestedMappings)
            .map(FormatConfig::formatMappingEntry)
            .windowed(elementsPerColumn, elementsPerColumn, true)

        // to properly align the columns within HTML use a table with fixed with cells
        val mappingsStringBuilder = StringBuilder()
        mappingsStringBuilder.append("<table>")
        for (i in 0..(elementsPerColumn.dec())) {
            mappingsStringBuilder.append("<tr>")
            for (column in windowedMappings) {
                val entry = column.getOrNull(i)
                if (entry != null) {
                    mappingsStringBuilder.append("<td width=\"${columnWidth}px\">$entry</td>")
                }
            }
            mappingsStringBuilder.append("</tr>")
        }
        mappingsStringBuilder.append("</table>")

        // append the already typed key sequence below the nested mappings table if configured (default: true)
        val showTypedSequence = when (val show = injector.variableService.getGlobalVariableValue("WhichKey_ShowTypedSequence")) {
            null -> true
            !is VimString -> true
            else -> show.asString().toBoolean()
        }
        if (showTypedSequence) {
            mappingsStringBuilder.append("<hr style=\"margin-bottom: 2px;\">") // some small margin to not look cramped
            mappingsStringBuilder.append(FormatConfig.formatTypedSequence(typedKeys))
        }

        val fadeoutTime = if (injector.globalOptions().timeout) {
            injector.globalOptions().timeoutlen.toLong()
        } else {
            0L
        }

        // render the HTML content in a non-editable JEditorPane with the theme's background color
        val bgColor = EditorColorsManager.getInstance().schemeForCurrentUITheme.defaultBackground
        val editorPane = JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            background = bgColor
            text = "<html><body style=\"background-color: #${Integer.toHexString(bgColor.rgb).substring(2)};\">${mappingsStringBuilder}</body></html>"
        }

        val position = popupPosition

        // create a lightweight JBPopup (no focus steal, with border, dismissible on click)
        val newPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(editorPane, null)
            .setRequestFocus(false)
            .setCancelOnClickOutside(cancelOnClickOutside)
            .setShowBorder(true)
            .createPopup()

        /*
         * wait for a few ms before showing the popup to prevent flickering on fast consecutive key presses
         * subtract the already passed time (for calculations etc.) to make the delay as consistent as possible
         */
        val delay = (defaultPopupDelay - (System.currentTimeMillis() - startTime)).coerceAtLeast(0)

        displayPopupJob = GlobalScope.launch {
            delay(delay)
            // JBPopup.show* must be called on the Event Dispatch Thread
            SwingUtilities.invokeLater {
                // position the popup within the IDE frame based on the configured position
                when (position) {
                    PopupPosition.CENTER -> newPopup.showInCenterOf(ideFrame.rootPane)
                    PopupPosition.TOP, PopupPosition.BOTTOM -> {
                        val rootPane = ideFrame.rootPane
                        val locationOnScreen = rootPane.locationOnScreen
                        val popupSize = editorPane.preferredSize
                        // horizontally centered
                        val x = locationOnScreen.x + (rootPane.width - popupSize.width) / 2
                        // vertically at the top or bottom edge
                        val y = if (position == PopupPosition.TOP) {
                            locationOnScreen.y
                        } else {
                            locationOnScreen.y + rootPane.height - popupSize.height
                        }
                        newPopup.showInScreenCoordinates(rootPane, Point(x, y))
                    }
                }
                currentPopup = newPopup

                // JBPopup has no built-in fadeout, so we use a one-shot Swing Timer
                // to auto-cancel the popup after the configured timeout
                if (fadeoutTime > 0) {
                    fadeoutTimer?.stop()
                    fadeoutTimer = Timer(fadeoutTime.toInt()) {
                        newPopup.cancel()
                        if (currentPopup == newPopup) {
                            currentPopup = null
                        }
                        fadeoutTimer = null
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }
            }
        }
    }

    /**
     * Sort mappings dependent on the configured sort options
     * @param nestedMappings The list of mappings to sort
     * @return The sorted list of mappings
     */
    private fun sortMappings(nestedMappings: List<Pair<String, Mapping>>): List<Pair<String, Mapping>> {
        // String::compareTo is by default case-sensitive
        val cmp = if (sortCaseSensitive) String::compareTo else String.CASE_INSENSITIVE_ORDER::compare

        return when (sortOption) {
            SortOption.BY_KEY -> nestedMappings.sortedWith(compareBy(cmp) { it.first })
            SortOption.BY_KEY_PREFIX_FIRST -> nestedMappings.sortedWith(compareBy<Pair<String, Mapping>> { !it.second.prefix }.thenBy(cmp) { it.first })
            SortOption.BY_KEY_PREFIX_LAST -> nestedMappings.sortedWith(compareBy<Pair<String, Mapping>> { it.second.prefix }.thenBy(cmp) { it.first })
            SortOption.BY_DESCRIPTION -> nestedMappings.sortedWith(compareBy(cmp) { it.second.description })
        }
    }
}

enum class SortOption {
    BY_KEY,
    BY_KEY_PREFIX_FIRST,
    BY_KEY_PREFIX_LAST,
    BY_DESCRIPTION
}

/**
 * Available positions for the Which-Key popup within the IDE frame.
 * Configurable via `g:WhichKey_Position` in `.ideavimrc`.
 */
enum class PopupPosition {
    CENTER,
    TOP,
    BOTTOM
}
