package io.github.yysassist.vision

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import io.github.yysassist.device.ScreenFrame
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns resource handoff, not task scheduling or game semantics. Caller retains pixels until return. */
internal class VersionedTextObserver(private val library: TaskVersionLibrary, private val backend: TextBackend) {
    suspend fun observe(version: String, models: OcrModelReferences, frame: ScreenFrame,
        permitted: () -> Boolean = { true }): TextReadResult = withContext(Dispatchers.IO) {
        if (!permitted()) return@withContext TextReadResult.Interrupted
        if (frame.bitmap.isRecycled || frame.bitmap.width != frame.stamp.geometry.width || frame.bitmap.height != frame.stamp.geometry.height)
            return@withContext TextReadResult.Unavailable(TextReadProblem.FRAME_INVALID)
        if (models.contract != backend.modelContract) return@withContext TextReadResult.Unavailable(TextReadProblem.UNSUPPORTED_MODEL)
        val lease = try { library.acquireResources(version) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: InvalidLocalTaskVersion) { return@withContext TextReadResult.Unavailable(TextReadProblem.RESOURCE_UNAVAILABLE, error.message) }
        catch (error: InvalidTaskResources) { return@withContext TextReadResult.Unavailable(TextReadProblem.RESOURCE_UNAVAILABLE, error.message) }
        catch (error: Exception) { return@withContext TextReadResult.Unavailable(TextReadProblem.STORAGE_ERROR, error.javaClass.simpleName) }
        var call: TextBackendCall? = null
        try {
            fun input(id: String): ModelInput {
                val descriptor = lease.descriptor(id)
                return ModelInput(lease.file(id), descriptor.bytes, descriptor.sha256)
            }
            val inputs = try { models.resources.mapValues { (_, id) -> input(id) } }
            catch (_: IllegalArgumentException) { return@withContext TextReadResult.Unavailable(TextReadProblem.RESOURCE_UNAVAILABLE) }
            currentCoroutineContext().ensureActive()
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                // The executor may have closed admission while model verification was in progress.
                if (!permitted()) return@withContext TextReadResult.Interrupted
                call = backend.start(inputs, pipe[0], frame.bitmap.width, frame.bitmap.height, lease::close)
                val active = checkNotNull(call)
                coroutineScope {
                    val producer = async(Dispatchers.IO) {
                        try {
                            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                                check(frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding rejected" }
                            }
                            Result.success(Unit)
                        } catch (error: Exception) { Result.failure<Unit>(error) }
                    }
                    try {
                        val result = active.result.awaitValue()
                        val encoded = producer.await()
                        when (result) {
                            is BackendTextResult.Unavailable -> TextReadResult.Unavailable(result.problem, result.detail, result.diagnostics)
                            is BackendTextResult.Observed -> if (encoded.isFailure) TextReadResult.Unavailable(TextReadProblem.ENCODING_FAILED, diagnostics = result.diagnostics)
                            else TextReadResult.Observed(frame.stamp, lease.version, lease.bundleDigest, result.candidates, result.diagnostics)
                        }
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
