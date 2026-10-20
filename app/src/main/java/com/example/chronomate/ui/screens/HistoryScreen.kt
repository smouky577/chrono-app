package com.example.chronomate.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chronomate.R
import com.example.chronomate.model.ChronoData
import com.example.chronomate.model.Shot
import com.example.chronomate.model.ShotSession
import com.example.chronomate.ui.components.ShotRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(data: ChronoData) {
    var showOld by rememberSaveable { mutableStateOf(false) }
    var openSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val openSession = data.savedSessions.firstOrNull { it.id == openSessionId }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!showOld) {
                Button(
                    onClick = {
                        showOld = false
                        openSessionId = null
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.history_new)) }
                OutlinedButton(
                    onClick = { showOld = true },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.history_old)) }
            } else {
                OutlinedButton(
                    onClick = {
                        showOld = false
                        openSessionId = null
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.history_new)) }
                Button(
                    onClick = {
                        showOld = true
                        openSessionId = null
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.history_old)) }
            }
        }

        when {
            !showOld -> ShotList(shots = data.shots, emptyText = stringResource(R.string.no_history))
            openSession != null -> Column(modifier = Modifier.fillMaxSize()) {
                TextButton(
                    onClick = { openSessionId = null },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.history_back))
                }
                Text(
                    text = sessionTitle(openSession.createdAtMillis),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                ShotList(shots = openSession.shots, emptyText = stringResource(R.string.no_history))
            }
            else -> SessionList(
                sessions = data.savedSessions,
                onOpen = { openSessionId = it.id }
            )
        }
    }
}

@Composable
private fun ShotList(shots: List<Shot>, emptyText: String) {
    if (shots.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(shots.asReversed().mapIndexed { index, shot -> index to shot }) { (reversedIndex, shot) ->
                val index = shots.size - reversedIndex
                ShotRow(shot = shot, index = index)
            }
        }
    }
}

@Composable
private fun SessionList(sessions: List<ShotSession>, onOpen: (ShotSession) -> Unit) {
    if (sessions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_saved_sessions), style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(session = session, onClick = { onOpen(session) })
            }
        }
    }
}

@Composable
private fun SessionRow(session: ShotSession, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sessionTitle(session.createdAtMillis),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.session_shot_count, session.shots.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun sessionTitle(createdAtMillis: Long): String {
    val formatted = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        .format(Date(createdAtMillis))
    return stringResource(R.string.session_title, formatted)
}
