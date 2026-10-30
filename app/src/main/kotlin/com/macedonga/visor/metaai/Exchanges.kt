package com.macedonga.visor.metaai

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.ANSWER_TOO_LATE
import com.macedonga.visor.core.assistant.ON_GLASSES
import com.macedonga.visor.core.assistant.TurnRecord
import com.macedonga.visor.core.assistant.TurnSource
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.gallery.PhotoLight
import com.macedonga.visor.gallery.clockTime
import com.macedonga.visor.gallery.dayTitle
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

internal val SIDE = 24.dp
private val LIGHT_OVERHANG = 96.dp

/** As tall as the parent and [by] more above and below it. */
private fun Modifier.overhang(by: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val placeable = measurable.measure(constraints.copy(minHeight = constraints.minHeight + 2 * extra, maxHeight = constraints.maxHeight + 2 * extra))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, -extra) }
}

/** A day's lines: the diary's own voice, a step larger than the exchanges under it. */
@Composable
internal fun ledeStyle(): TextStyle = Visor.type.body.copy(fontSize = 19.sp, lineHeight = 27.sp, letterSpacing = (-0.012).em)

/**
 * A day's head: its name and date, which open the calendar; its lines once written; its photos, small and
 * overlapping, each a way to its exchange.
 */
@Composable
internal fun DayHead(day: DiaryDay, first: Boolean, onCalendar: () -> Unit, onPhoto: (String) -> Unit, onLongPress: () -> Unit) {
    val colors = Visor.colors
    val relative = day.date == LocalDate.now() || day.date == LocalDate.now().minusDays(1)
    val full = DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.getDefault()).format(day.date).replaceFirstChar { it.titlecase() }
    val sub = listOfNotNull(full.takeIf { relative }, pluralStringResource(R.plurals.meta_ai_questions, day.questions, day.questions)).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(top = if (first) 4.dp else 44.dp).animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures(onTap = { onCalendar() }, onLongPress = { onLongPress() }) }
                .padding(horizontal = SIDE, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(dayTitle(day.date), style = Visor.type.heading, color = colors.textPrimary, modifier = Modifier.asHeading())
                Text(sub, style = Visor.type.label, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(VisorIcons.ChevronDown, stringResource(R.string.meta_ai_pick_day), Modifier.size(18.dp), tint = colors.textTertiary)
        }
        day.lines?.let { lines ->
            Text(lines.text, style = ledeStyle(), color = colors.textPrimary, modifier = Modifier.padding(start = SIDE, end = SIDE, top = 10.dp))
        }
        val photos = day.photos
        if (photos.isNotEmpty()) PhotoStack(photos, onPhoto, Modifier.padding(start = SIDE, top = 16.dp))
    }
}

/** Up to four photos laid over each other like prints; each opens its exchange. */
@Composable
private fun PhotoStack(photos: List<TurnRecord>, onPhoto: (String) -> Unit, modifier: Modifier) {
    val shown = photos.take(4)
    Box(modifier.height(48.dp).width((48 + (shown.size - 1) * 34).dp)) {
        for ((i, turn) in shown.withIndex()) {
            val image by rememberDiaryPhoto(turn.image, 192)
            Box(
                Modifier
                    .offset(x = (i * 34).dp)
                    .size(48.dp)
                    .graphicsLayer { rotationZ = if (i % 2 == 0) -3f else 3f }
                    .clip(RoundedCornerShape(11.dp))
                    .border(2.dp, Visor.colors.canvas, RoundedCornerShape(11.dp))
                    .background(Visor.colors.raised)
                    .clickable { onPhoto(turn.interactionId) },
            ) {
                image?.let { Image(it, stringResource(R.string.gallery_photo), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
    }
}

/** One conversation of a day: its exchanges in order, and where it began if not today. */
@Composable
internal fun ConversationBlock(
    conversation: DiaryConversation,
    speech: Speech,
    onLongPress: (TurnRecord) -> Unit,
    onPhoto: (String) -> Unit,
    onMemories: () -> Unit,
    onExport: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
        conversation.startedOn?.let {
            Text(
                stringResource(R.string.meta_ai_continues, dayTitle(it)),
                style = Visor.type.label,
                color = Visor.colors.textTertiary,
                modifier = Modifier.padding(start = SIDE, end = SIDE, bottom = 12.dp),
            )
        }
        for ((i, turn) in conversation.turns.withIndex()) {
            if (i > 0) Spacer(Modifier.height(24.dp))
            Exchange(turn, speech.shownOf(turn), { onLongPress(turn) }, onPhoto, onMemories)
        }
        Row(
            Modifier.padding(start = SIDE, end = SIDE, top = 18.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Visor.colors.raised)
                .clickable(onClick = onExport)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(VisorIcons.Share, null, Modifier.size(16.dp), tint = Visor.colors.textSecondary)
            Text(
                stringResource(R.string.meta_ai_export_conversation),
                style = Visor.type.label,
                color = Visor.colors.textSecondary,
                modifier = Modifier.padding(start = 9.dp),
            )
        }
    }
}

/**
 * A question and its answer. The time and where it was asked, the question in grey, the photo it came with lighting
 * the whole exchange, the answer in white, word by word while it is being spoken; what the model kept, under it.
 */
@Composable
internal fun Exchange(turn: TurnRecord, shown: Int, onLongPress: () -> Unit, onPhoto: (String) -> Unit, onMemories: () -> Unit) {
    val colors = Visor.colors
    val photo = turn.image.takeIf { it.isNotEmpty() }
    val image by rememberDiaryPhoto(photo)
    val uriHandler = LocalUriHandler.current
    Box(Modifier.fillMaxWidth()) {
        // Past the exchange's edges, and out to nothing before its own: a light cut off at a bound blurs into a line.
        if (photo != null) PhotoLight(image, Modifier.matchParentSize().overhang(LIGHT_OVERHANG), level = 0.48f, radius = 0.5f)
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(turn.interactionId) { detectTapGestures(onLongPress = { onLongPress() }) }
                .padding(horizontal = SIDE),
        ) {
            val where = if (turn.source == TurnSource.PHONE) " · " + stringResource(R.string.meta_ai_phone) else ""
            Text(clockTime(turn.at) + where, style = Visor.type.label, color = colors.textTertiary)
            Text(turn.question.ifBlank { "…" }, style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
            if (photo != null) {
                Box(
                    Modifier
                        .padding(vertical = 14.dp)
                        .fillMaxWidth()
                        .aspectRatio(4f / 5f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.raised)
                        .clickable { onPhoto(photo) },
                ) {
                    image?.let { Image(it, stringResource(R.string.meta_ai_photo), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
            Answer(turn, shown)
            if (turn.sources.isNotEmpty()) {
                Text(
                    stringResource(R.string.meta_ai_web_sources),
                    style = Visor.type.label,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                )
                for (source in turn.sources) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { runCatching { uriHandler.openUri(source.url) } }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(VisorIcons.Share, null, Modifier.size(15.dp), tint = colors.textSecondary)
                        Text(
                            source.title.ifBlank { source.url },
                            style = Visor.type.label,
                            color = colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            for (fact in turn.remembered) {
                Row(
                    Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onMemories).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(VisorIcons.Memory, null, Modifier.size(15.dp), tint = colors.textTertiary)
                    Text(
                        stringResource(R.string.meta_ai_remembered, fact),
                        style = Visor.type.label,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            // What the assistant did with the person's integrations, as quietly as what it remembered.
            for (action in turn.actions) {
                val tint = if (action.ok) colors.textTertiary else colors.signal
                Row(Modifier.padding(top = 10.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(VisorIcons.Plug, null, Modifier.size(15.dp), tint = tint)
                    Text(
                        stringResource(if (action.ok) R.string.meta_ai_action else R.string.meta_ai_action_failed, action.server, action.what),
                        style = Visor.type.label,
                        color = tint,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Answer(turn: TurnRecord, shown: Int) {
    val colors = Visor.colors
    when {
        turn.problem == ON_GLASSES -> Text(stringResource(R.string.meta_ai_on_glasses), style = Visor.type.label, color = colors.textTertiary)
        turn.problem.isNotEmpty() && turn.problem != ANSWER_TOO_LATE ->
            Text(stringResource(R.string.meta_ai_no_answer, turn.problem), style = Visor.type.label, color = colors.textTertiary)
        turn.answer.isEmpty() -> Thinking()
        else -> {
            val text = turn.answer
            // The words not yet spoken hold their place, so nothing moves as they come.
            val annotated = if (shown >= text.length) buildAnnotatedString { append(text) } else buildAnnotatedString {
                append(text.substring(0, shown))
                withStyle(SpanStyle(color = Color.Transparent)) { append(text.substring(shown)) }
            }
            Text(annotated, style = Visor.type.body, color = colors.textPrimary)
            if (turn.problem == ANSWER_TOO_LATE) {
                Text(stringResource(R.string.meta_ai_too_late), style = Visor.type.label, color = colors.textTertiary, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Three dots breathing one after the other: the model is on it. */
@Composable
internal fun Thinking(modifier: Modifier = Modifier) {
    val beat = rememberInfiniteTransition(label = "thinking")
    val t by beat.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "t")
    Row(modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 3) {
            val phase = ((t - i * 0.18f) % 1f + 1f) % 1f
            val a = 0.25f + 0.75f * (1f - kotlin.math.abs(phase * 2f - 1f))
            Box(Modifier.size(6.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(Visor.colors.textSecondary))
        }
    }
}

/** A Live AI session, folded into one card: how long, a strip of what the camera saw, what was asked. */
@Composable
internal fun LiveBlock(conversation: DiaryConversation, onOpen: () -> Unit, onExport: () -> Unit) {
    val colors = Visor.colors
    val turns = conversation.turns
    val first = turns.first()
    val last = turns.last()
    val minutes = ceil((last.at + last.modelMs + SPOKEN_TAIL_MS - first.at) / 60_000.0).toInt().coerceAtLeast(1)
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.raised)
            .clickable(onClick = onOpen)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(VisorIcons.Live, null, Modifier.size(18.dp), tint = colors.textPrimary)
            Text(
                stringResource(R.string.meta_ai_live_length, minutes),
                style = Visor.type.label,
                color = colors.textPrimary,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            Text(clockTime(first.at), style = Visor.type.label, color = colors.textSecondary)
        }
        val frames = turns.filter { it.image.isNotEmpty() }.take(5)
        if (frames.isNotEmpty()) {
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (frame in frames) {
                    val image by rememberDiaryPhoto(frame.image, 160)
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(colors.control)) {
                        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
            }
        }
        Text(
            turns.joinToString(" · ") { "“${it.question.trim()}”" },
            style = Visor.type.body,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(
            Modifier.padding(top = 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onExport)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(VisorIcons.Share, null, Modifier.size(16.dp), tint = colors.textSecondary)
            Text(
                stringResource(R.string.meta_ai_export_conversation),
                style = Visor.type.label,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 9.dp),
            )
        }
    }
}

/** A photo on its own, lit by itself; a tap anywhere puts it back. */
@Composable
internal fun PhotoView(path: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val image by rememberDiaryPhoto(path, 2048)
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            PhotoLight(image, Modifier.fillMaxSize(), level = 0.7f)
            image?.let {
                Image(it, stringResource(R.string.meta_ai_photo), Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Fit)
            }
        }
    }
}

// How long the last answer of a session is taken to have gone on after it was ready.
private const val SPOKEN_TAIL_MS = 5_000L
