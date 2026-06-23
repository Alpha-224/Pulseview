package com.Twinhealth.pulseview

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

class OnnxInference(context: Context) {

    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open("efficientphys.onnx").readBytes()
        val sessionOptions = OrtSession.SessionOptions()
        session = ortEnvironment.createSession(modelBytes, sessionOptions)
    }

    /**
     * Run EfficientPhys on a batch of preprocessed frames.
     *
     * @param frames FloatArray of shape (N, 3, 72, 72) flattened, where N = 21
     *               (20 frames + 1 duplicate last frame, matching training).
     * @param numFrames N — total frame count fed in (21)
     * @return FloatArray of BVP values, length N-1 (20)
     */
    fun runInference(frames: FloatArray, numFrames: Int, imgSize: Int = 72): FloatArray {
        val shape = longArrayOf(numFrames.toLong(), 3, imgSize.toLong(), imgSize.toLong())
        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(frames),
            shape
        )

        val inputs = mapOf("frames" to inputTensor)
        val output = session.run(inputs)

        // Output shape: (numFrames - 1, 1)
        @Suppress("UNCHECKED_CAST")
        val rawOutput = output[0].value as Array<FloatArray>
        val bvp = FloatArray(rawOutput.size) { i -> rawOutput[i][0] }

        inputTensor.close()
        output.close()

        return bvp
    }

    fun close() {
        session.close()
    }
}