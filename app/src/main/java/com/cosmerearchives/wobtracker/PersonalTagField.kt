package com.cosmerearchives.wobtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Suggests previously saved personal tags for the current comma-separated segment.
 * Never changes other tags or saves before the parent editor's save action.
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
    val fragment = value.substringAfterLast(',').trim()
    val alreadyEntered = value.substringBeforeLast(',', "").split(',')
        .map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
    val suggestions = if (focused && fragment.isNotEmpty()) {
        allTags.filter {
            it.contains(fragment, ignoreCase = true) &&
                it.lowercase(Locale.ROOT) !in alreadyEntered &&
                !it.equals(fragment, ignoreCase = true)
        }.take(6)
    } else emptyList()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); focused = true },
            label = { Text("My tags (comma separated)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        if (suggestions.isNotEmpty()) {
            Text("Previously used tags", style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { suggestion ->
                    SuggestionChip(
                        onClick = {
                            val prefix = value.substringBeforeLast(',', "")
                            val result = if (',' in value) prefix.trimEnd() + ", " + suggestion else suggestion
                            onValueChange(result + ", ")
                            focused = false
                        },
                        label = { Text(suggestion) }
                    )
                }
            }
        }
    }
}
