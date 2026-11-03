package com.cosmerearchives.wobtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Append a whole tag, preserving the existing list and leaving ", " ready for the next one. */
fun appendPersonalTag(value: String, suggestion: String): String {
    val tag = suggestion.trim()
    if (tag.isEmpty()) return value
    val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.any { it.equals(tag, ignoreCase = true) }) return value
    return (parts + tag).joinToString(", ") + ", "
}

/** Replace only the partially typed final tag, for autocomplete selection. */
private fun completePersonalTag(value: String, suggestion: String): String {
    val existing = value.substringBeforeLast(',', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (existing.any { it.equals(suggestion, ignoreCase = true) }) {
        return if (existing.isEmpty()) "" else existing.joinToString(", ") + ", "
    }
    return (existing + suggestion).joinToString(", ") + ", "
}

/**
 * Quick picks are the user's most frequently saved tags; typed autocomplete is retained.
 * No selection saves until the parent review/library save action.
 */
@Composable
fun PersonalTagField(
    value: String,
    onValueChange: (String) -> Unit,
    library: WobLibrary,
    revision: Int,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val allTags = remember(library, revision) { library.knownPersonalTags() }
    val frequentTags = remember(library, revision) { library.frequentPersonalTags(5) }
    val entered = value.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
    val fragment = value.substringAfterLast(',').trim()
    val prefixTags = value.substringBeforeLast(',', "").split(',')
        .map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
    val suggestions = if (focused && fragment.isNotEmpty()) {
        allTags.filter {
            it.contains(fragment, ignoreCase = true) &&
                it.lowercase(Locale.ROOT) !in prefixTags &&
                !it.equals(fragment, ignoreCase = true)
        }.take(6)
    } else emptyList()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val quickTags = frequentTags.filter { it.lowercase(Locale.ROOT) !in entered }
        if (quickTags.isNotEmpty()) {
            Text("Frequently used tags", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quickTags.forEach { tag ->
                    SuggestionChip(
                        onClick = { onValueChange(appendPersonalTag(value, tag)) },
                        label = { Text(tag) }
                    )
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); focused = true },
            label = { Text("My tags (comma separated)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        if (suggestions.isNotEmpty()) {
            Text("Matching existing tags", style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { suggestion ->
                    SuggestionChip(
                        onClick = {
                            onValueChange(completePersonalTag(value, suggestion))
                            focused = false
                        },
                        label = { Text(suggestion) }
                    )
                }
            }
        }
    }
}

/** Source tags are clickable shortcuts into personal tags; original source tags remain untouched. */
@Composable
fun SourceTagChips(sourceTags: String, personalTags: String, onAdd: (String) -> Unit) {
    val tags = sourceTags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }
    if (tags.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Source tags (tap to add)", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { tag ->
                val added = personalTags.split(',').any { it.trim().equals(tag, ignoreCase = true) }
                SuggestionChip(
                    enabled = !added,
                    onClick = { onAdd(tag) },
                    label = { Text(if (added) "✓ $tag" else tag) }
                )
            }
        }
    }
}
