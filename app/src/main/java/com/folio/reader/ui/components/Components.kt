package com.folio.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.folio.reader.data.db.BookEntity
import com.folio.reader.ui.theme.Folio
import com.folio.reader.ui.theme.SerifDisplay
import java.io.File
import kotlin.math.absoluteValue

@Composable
fun BookCover(book: BookEntity, modifier: Modifier = Modifier, corner: Dp = 12.dp, elevation: Dp = 6.dp) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .shadow(elevation, shape, clip = false, ambientColor = Folio.Ink.copy(alpha = .25f), spotColor = Folio.Ink.copy(alpha = .35f))
            .clip(shape)
    ) {
        if (book.coverPath != null && File(book.coverPath).exists()) {
            AsyncImage(
                model = File(book.coverPath),
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CoverPlaceholder(book.title, book.author)
        }
        // Subtle spine shading, like a real paperback.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .14f), .06f to Color.Transparent))
        )
    }
}

@Composable
private fun CoverPlaceholder(title: String, author: String) {
    val (a, b) = Folio.coverPalettes[title.hashCode().absoluteValue % Folio.coverPalettes.size]
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(a, b))),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                color = Color.White,
                fontFamily = SerifDisplay,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                author,
                color = Color.White.copy(alpha = .8f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondary, height: Dp = 5.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

/** Renders the tiny markdown subset the AI uses: **bold** and "- " bullets. */
fun markdownLite(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, rawLine ->
        if (i > 0) append('\n')
        val line = if (rawLine.trimStart().startsWith("- ") || rawLine.trimStart().startsWith("* ")) {
            "•  " + rawLine.trimStart().drop(2)
        } else rawLine.removePrefix("#").removePrefix("#").removePrefix("# ")
        var rest = line
        while (rest.isNotEmpty()) {
            val start = rest.indexOf("**")
            val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
            if (start < 0 || end < 0) {
                append(rest); break
            }
            append(rest.substring(0, start))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(start + 2, end)) }
            rest = rest.substring(end + 2)
        }
    }
}
