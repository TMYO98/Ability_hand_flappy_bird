package com.ability_flappy_bird.ability_flappy_bird

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ability_flappy_bird.ability_flappy_bird.ui.theme.Ability_flappy_birdTheme
import kotlinx.coroutines.delay
import kotlin.random.Random

// ── Constants ────────────────────────────────────────────────────────────────
private const val GRAVITY = 0.6f
private const val FLAP_STRENGTH = -12f
private const val PIPE_SPEED = 5f
private const val PIPE_WIDTH = 90f
private const val PIPE_GAP = 230f
private const val BIRD_RADIUS = 28f
private const val GROUND_HEIGHT = 80f
private const val PIPE_SPAWN_EVERY = 85  // frames between spawns
private const val FRAME_DELAY_MS = 16L   // ~60 fps

// ── Game model ───────────────────────────────────────────────────────────────
enum class GamePhase { WAITING, PLAYING, DEAD }

data class Pipe(
    val x: Float,
    val gapY: Float,       // centre of the gap
    val scored: Boolean = false
)

data class GameState(
    val phase: GamePhase = GamePhase.WAITING,
    val birdY: Float = 0f,
    val birdVel: Float = 0f,
    val pipes: List<Pipe> = emptyList(),
    val score: Int = 0,
    val frame: Int = 0,
    val w: Float = 0f,
    val h: Float = 0f
) {
    val birdX get() = w * 0.25f
}

// ── Activity ─────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ability_flappy_birdTheme {
                FlappyBirdGame()
            }
        }
    }
}

// ── Root composable ───────────────────────────────────────────────────────────
@Composable
fun FlappyBirdGame() {
    var state by remember { mutableStateOf(GameState()) }

    // Game loop – runs only while PLAYING
    LaunchedEffect(state.phase) {
        if (state.phase == GamePhase.PLAYING) {
            while (state.phase == GamePhase.PLAYING) {
                delay(FRAME_DELAY_MS)
                state = tick(state)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF70C5CE))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { state = onTap(state) }
    ) {
        // Game canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            // First frame: capture screen dimensions and centre bird
            if (state.w == 0f) {
                state = state.copy(w = size.width, h = size.height, birdY = size.height / 2f)
            }
            drawScene(state)
        }

        // Score (only while playing)
        if (state.phase == GamePhase.PLAYING) {
            Text(
                text = state.score.toString(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp),
                fontSize = 52.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        }

        // Overlay (start / game-over)
        if (state.phase != GamePhase.PLAYING) {
            Overlay(
                modifier = Modifier.align(Alignment.Center),
                phase = state.phase,
                score = state.score
            )
        }
    }
}

// ── Overlay composable ────────────────────────────────────────────────────────
@Composable
fun Overlay(modifier: Modifier, phase: GamePhase, score: Int) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (phase == GamePhase.WAITING) "Flappy Bird" else "Game Over",
            fontSize = 44.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )
        Spacer(Modifier.height(12.dp))
        if (phase == GamePhase.DEAD) {
            Text(
                text = "Score: $score",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = if (phase == GamePhase.WAITING) "Tap to start" else "Tap to play again",
            fontSize = 22.sp,
            color = Color.White
        )
    }
}

// ── Input ─────────────────────────────────────────────────────────────────────
private fun onTap(state: GameState): GameState = when (state.phase) {
    GamePhase.WAITING -> state.copy(
        phase = GamePhase.PLAYING,
        birdY = state.h / 2f,
        birdVel = FLAP_STRENGTH,
        pipes = emptyList(),
        score = 0,
        frame = 0
    )
    GamePhase.PLAYING -> state.copy(birdVel = FLAP_STRENGTH)
    GamePhase.DEAD -> state.copy(
        phase = GamePhase.WAITING,
        birdY = state.h / 2f,
        birdVel = 0f,
        pipes = emptyList(),
        score = 0,
        frame = 0
    )
}

// ── Physics / game tick ───────────────────────────────────────────────────────
private fun tick(s: GameState): GameState {
    val newVel = s.birdVel + GRAVITY
    val newY   = s.birdY  + newVel
    val newFrame = s.frame + 1

    // Spawn pipe
    var pipes = s.pipes
    if (newFrame % PIPE_SPAWN_EVERY == 0) {
        val minGap  = PIPE_GAP / 2f + 60f
        val maxGap  = s.h - GROUND_HEIGHT - PIPE_GAP / 2f - 60f
        val centreY = Random.nextFloat() * (maxGap - minGap) + minGap
        pipes = pipes + Pipe(x = s.w, gapY = centreY)
    }

    // Move pipes
    pipes = pipes.map { it.copy(x = it.x - PIPE_SPEED) }

    // Remove off-screen
    pipes = pipes.filter { it.x + PIPE_WIDTH > 0f }

    // Score
    var score = s.score
    pipes = pipes.map { pipe ->
        if (!pipe.scored && pipe.x + PIPE_WIDTH < s.birdX) {
            score++
            pipe.copy(scored = true)
        } else pipe
    }

    // Collision
    val top    = newY - BIRD_RADIUS
    val bottom = newY + BIRD_RADIUS
    val left   = s.birdX - BIRD_RADIUS
    val right  = s.birdX + BIRD_RADIUS

    val hitBounds = top <= 0f || bottom >= s.h - GROUND_HEIGHT

    val hitPipe = pipes.any { pipe ->
        val xOverlap = right > pipe.x && left < pipe.x + PIPE_WIDTH
        if (!xOverlap) return@any false
        val gapTop    = pipe.gapY - PIPE_GAP / 2f
        val gapBottom = pipe.gapY + PIPE_GAP / 2f
        top < gapTop || bottom > gapBottom
    }

    val phase = if (hitBounds || hitPipe) GamePhase.DEAD else GamePhase.PLAYING

    return s.copy(
        birdY  = newY,
        birdVel = newVel,
        pipes  = pipes,
        score  = score,
        frame  = newFrame,
        phase  = phase
    )
}

// ── Drawing ───────────────────────────────────────────────────────────────────
private fun DrawScope.drawScene(s: GameState) {
    if (s.h == 0f) return

    // Background clouds (static decorative)
    drawCloud(Offset(s.w * 0.15f, s.h * 0.12f), 60f)
    drawCloud(Offset(s.w * 0.55f, s.h * 0.08f), 45f)
    drawCloud(Offset(s.w * 0.80f, s.h * 0.18f), 55f)

    // Pipes
    for (pipe in s.pipes) {
        drawPipe(pipe, s.h)
    }

    // Ground
    drawRect(
        color = Color(0xFFDEB887),
        topLeft = Offset(0f, s.h - GROUND_HEIGHT),
        size = Size(s.w, GROUND_HEIGHT)
    )
    drawRect(
        color = Color(0xFF4D8C2A),
        topLeft = Offset(0f, s.h - GROUND_HEIGHT),
        size = Size(s.w, 22f)
    )

    // Bird
    val tilt = (s.birdVel * 2.5f).coerceIn(-25f, 70f)
    drawBird(Offset(s.birdX, s.birdY), tilt)
}

private fun DrawScope.drawCloud(center: Offset, r: Float) {
    val c = Color(0xCCFFFFFF)
    drawCircle(c, r, center)
    drawCircle(c, r * 0.75f, center + Offset(r * 1.0f, r * 0.1f))
    drawCircle(c, r * 0.70f, center + Offset(-r * 0.9f, r * 0.1f))
    drawCircle(c, r * 0.60f, center + Offset(r * 0.5f, -r * 0.3f))
}

private fun DrawScope.drawPipe(pipe: Pipe, screenH: Float) {
    val gapTop    = pipe.gapY - PIPE_GAP / 2f
    val gapBottom = pipe.gapY + PIPE_GAP / 2f
    val capH = 36f
    val capExtra = 12f

    // Top shaft
    drawRect(
        color = Color(0xFF4CAF50),
        topLeft = Offset(pipe.x, 0f),
        size = Size(PIPE_WIDTH, gapTop - capH)
    )
    // Top cap
    drawRect(
        color = Color(0xFF388E3C),
        topLeft = Offset(pipe.x - capExtra, gapTop - capH),
        size = Size(PIPE_WIDTH + capExtra * 2, capH)
    )

    // Bottom cap
    drawRect(
        color = Color(0xFF388E3C),
        topLeft = Offset(pipe.x - capExtra, gapBottom),
        size = Size(PIPE_WIDTH + capExtra * 2, capH)
    )
    // Bottom shaft
    drawRect(
        color = Color(0xFF4CAF50),
        topLeft = Offset(pipe.x, gapBottom + capH),
        size = Size(PIPE_WIDTH, screenH - gapBottom - capH)
    )

    // Pipe sheen
    drawRect(
        color = Color(0x3366FF66),
        topLeft = Offset(pipe.x + 8f, 0f),
        size = Size(14f, gapTop - capH)
    )
    drawRect(
        color = Color(0x3366FF66),
        topLeft = Offset(pipe.x + 8f, gapBottom + capH),
        size = Size(14f, screenH - gapBottom - capH)
    )
}

private fun DrawScope.drawBird(center: Offset, tiltDeg: Float) {
    rotate(degrees = tiltDeg, pivot = center) {
        // Body
        drawCircle(Color(0xFFFFD600), BIRD_RADIUS, center)

        // Wing
        drawCircle(
            color = Color(0xFFFFA000),
            radius = BIRD_RADIUS * 0.55f,
            center = center + Offset(-BIRD_RADIUS * 0.3f, BIRD_RADIUS * 0.35f)
        )

        // Belly highlight
        drawCircle(
            color = Color(0xFFFFF176),
            radius = BIRD_RADIUS * 0.45f,
            center = center + Offset(BIRD_RADIUS * 0.1f, BIRD_RADIUS * 0.15f)
        )

        // Eye white
        drawCircle(
            color = Color.White,
            radius = BIRD_RADIUS * 0.42f,
            center = center + Offset(BIRD_RADIUS * 0.28f, -BIRD_RADIUS * 0.18f)
        )
        // Pupil
        drawCircle(
            color = Color.Black,
            radius = BIRD_RADIUS * 0.20f,
            center = center + Offset(BIRD_RADIUS * 0.40f, -BIRD_RADIUS * 0.18f)
        )
        // Eye shine
        drawCircle(
            color = Color.White,
            radius = BIRD_RADIUS * 0.07f,
            center = center + Offset(BIRD_RADIUS * 0.46f, -BIRD_RADIUS * 0.26f)
        )

        // Beak
        val beakTip = center + Offset(BIRD_RADIUS * 1.38f, BIRD_RADIUS * 0.05f)
        val beakPath = Path().apply {
            moveTo(center.x + BIRD_RADIUS * 0.75f, center.y - BIRD_RADIUS * 0.05f)
            lineTo(beakTip.x, beakTip.y - BIRD_RADIUS * 0.12f)
            lineTo(beakTip.x, beakTip.y + BIRD_RADIUS * 0.12f)
            lineTo(center.x + BIRD_RADIUS * 0.75f, center.y + BIRD_RADIUS * 0.25f)
            close()
        }
        drawPath(beakPath, Color(0xFFFF6D00))
    }
}
