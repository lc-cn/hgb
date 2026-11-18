package io.github.yysassist.vision

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import io.github.yysassist.device.FrameStamp
import io.github.yysassist.device.ScreenFrame
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns resource handoff, not task scheduling or game semantics. Caller retains pixels until return. */
internal class VersionedTextObserver(private val library: TaskVersionLibrary, private val backend: TextBackend) {
    private sealed interface Outcome<out T> {
        data object Interrupted : Outcome<Nothing>
        data class Failed(val problem: TextReadProblem, val detail: String? = null, val diagnostics: List<String> = emptyList()) : Outcome<Nothing>
        data class Done<T>(val value: T, val version: String, val bundleDigest: String) : Outcome<T>
    }

    suspend fun observe(version: String, models: OcrModelReferences, frame: ScreenFrame,
        permitted: () -> Boolean = { true }): TextReadResult = withContext(Dispatchers.IO) {
        if (!permitted()) return@withContext TextReadResult.Interrupted
        if (frame.bitmap.isRecycled || frame.bitmap.width != frame.stamp.geometry.width || frame.bitmap.height != frame.stamp.geometry.height)
            return@withContext TextReadResult.Unavailable(TextReadProblem.FRAME_INVALID)
        var encoded: EncodedPixels? = null
        var encodeMillis = -1L
        val started = android.os.SystemClock.elapsedRealtime()
        val outcome = run(version, models, emptyList(), permitted, write = { output ->
            // Retained so region requests later in this attempt inspect exactly these pixels.
            val encodeStarted = android.os.SystemClock.elapsedRealtime()
            val pixels = if (backend.rawPixels) EncodedPixels.raw(frame.bitmap) else EncodedPixels(ByteArrayOutputStream().also {
                check(frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding rejected" } }.toByteArray(), frame.bitmap.width, frame.bitmap.height)
            encodeMillis = android.os.SystemClock.elapsedRealtime() - encodeStarted
            encoded = pixels; output.write(pixels.bytes)
        }) { inputs, _, read, onQuiet -> backend.start(inputs, read, frame.bitmap.width, frame.bitmap.height, onQuiet) }
        when (outcome) {
            Outcome.Interrupted -> TextReadResult.Interrupted
            is Outcome.Failed -> TextReadResult.Unavailable(outcome.problem, outcome.detail, outcome.diagnostics)
            is Outcome.Done -> when (val result = outcome.value) {
                is BackendTextResult.Unavailable -> TextReadResult.Unavailable(result.problem, result.detail, result.diagnostics)
                is BackendTextResult.Observed -> TextReadResult.Observed(frame.stamp, outcome.version, outcome.bundleDigest, result.candidates,
                    result.diagnostics, encoded,
                    result.timings + mapOf("encodeMs" to encodeMillis, "observeMs" to android.os.SystemClock.elapsedRealtime() - started))
            }
        }
    }

    /** Pixels of a captured frame in the form the backend expects, without running recognition. */
    fun pixelsOf(frame: ScreenFrame): EncodedPixels = if (backend.rawPixels) EncodedPixels.raw(frame.bitmap) else EncodedPixels(
        ByteArrayOutputStream().also { check(frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray(), frame.bitmap.width, frame.bitmap.height)

    /** Region requests over pixels already observed in this attempt; never takes a new capture. */
    suspend fun inspect(version: String, models: OcrModelReferences, stamp: FrameStamp, pixels: EncodedPixels,
        requests: List<InspectionRequest>, permitted: () -> Boolean = { true }): InspectionResult = withContext(Dispatchers.IO) {
        if (!permitted()) return@withContext InspectionResult.Interrupted
        if (pixels.width != stamp.geometry.width || pixels.height != stamp.geometry.height || requests.isEmpty() || pixels.raw != backend.rawPixels)
            return@withContext InspectionResult.Unavailable(TextReadProblem.FRAME_INVALID)
        val templates = requests.filterIsInstance<InspectionRequest.Image>().map { it.template }.distinct()
        val started = android.os.SystemClock.elapsedRealtime()
        val outcome = run(version, models, templates, permitted, write = { it.write(pixels.bytes) }) { inputs, templateInputs, read, onQuiet ->
            backend.inspect(inputs, templateInputs, requests, read, pixels.width, pixels.height, onQuiet)
        }
        when (outcome) {
            Outcome.Interrupted -> InspectionResult.Interrupted
            is Outcome.Failed -> InspectionResult.Unavailable(outcome.problem, outcome.detail, outcome.diagnostics)
            is Outcome.Done -> when (val result = outcome.value) {
                is BackendInspectionResult.Unavailable -> InspectionResult.Unavailable(result.problem, result.detail, result.diagnostics)
                is BackendInspectionResult.Inspected -> if (outcome.version != version) InspectionResult.Unavailable(TextReadProblem.RESOURCE_UNAVAILABLE)
                    else InspectionResult.Inspected(stamp, outcome.version, result.values, result.diagnostics,
                        result.timings + ("inspectMs" to android.os.SystemClock.elapsedRealtime() - started))
            }
        }
    }

    private suspend fun <T> run(version: String, models: OcrModelReferences, templates: List<String>, permitted: () -> Boolean,
        write: (java.io.OutputStream) -> Unit,
        start: (Map<String, ModelInput>, Map<String, ModelInput>, ParcelFileDescriptor, () -> Unit) -> BackendCall<T>): Outcome<T> {
        if (models.contract != backend.modelContract) return Outcome.Failed(TextReadProblem.UNSUPPORTED_MODEL)
        val lease = try { library.acquireResources(version) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: InvalidLocalTaskVersion) { return Outcome.Failed(TextReadProblem.RESOURCE_UNAVAILABLE, error.message) }
        catch (error: InvalidTaskResources) { return Outcome.Failed(TextReadProblem.RESOURCE_UNAVAILABLE, error.message) }
        catch (error: Exception) { return Outcome.Failed(TextReadProblem.STORAGE_ERROR, error.javaClass.simpleName) }
        var call: BackendCall<T>? = null
        try {
            fun input(id: String): ModelInput {
                val descriptor = lease.descriptor(id)
                return ModelInput(lease.file(id), descriptor.bytes, descriptor.sha256)
            }
            val inputs = try { models.resources.mapValues { (_, id) -> input(id) } }
            catch (_: IllegalArgumentException) { return Outcome.Failed(TextReadProblem.RESOURCE_UNAVAILABLE) }
            val templateInputs = try { templates.associateWith { input(it) } }
            catch (_: IllegalArgumentException) { return Outcome.Failed(TextReadProblem.RESOURCE_UNAVAILABLE) }
            currentCoroutineContext().ensureActive()
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                // The executor may have closed admission while model verification was in progress.
                if (!permitted()) return Outcome.Interrupted
                call = start(inputs, templateInputs, pipe[0], lease::close)
                val active = checkNotNull(call)
                return coroutineScope {
                    val producer = async(Dispatchers.IO) {
                        try {
                            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use(write)
                            Result.success(Unit)
                        } catch (error: Exception) { Result.failure<Unit>(error) }
                    }
                    try {
                        val result = active.result.awaitValue()
                        val encoded = producer.await()
                        val failed = (result as? BackendTextResult.Observed)?.diagnostics ?: (result as? BackendInspectionResult.Inspected)?.diagnostics
                        if (failed != null && encoded.isFailure) Outcome.Failed(TextReadProblem.ENCODING_FAILED, diagnostics = failed)
                        else Outcome.Done(result, lease.version, lease.bundleDigest)
                    } finally {
                        // Cancellation requests stop; it cannot close a foreign file lease or recycle
                        // caller pixels while a blocked PNG writer may still be using the bitmap.
                        withContext(NonCancellable) {
                            active.requestStop()
                            active.quiet.awaitValue()
                            pipe[1].close()
                            producer.join()
                        }
                    }
                }
            } finally {
                if (call == null) pipe[0].close() // Otherwise ownership belongs to the backend.
                pipe[1].close()
            }
        } finally {
            // Once adopted, only the backend's independently verified quiet callback closes it.
            if (call == null) lease.close()
        }
    }
}

internal suspend fun <T> CompletableFuture<T>.awaitValue(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (continuation.isActive) {
            if (error == null) continuation.resume(value)
            else continuation.resumeWithException((error as? CompletionException)?.cause ?: error)
        }
    }
    // Do not cancel an underlying native operation through Java Future cancellation.
}
