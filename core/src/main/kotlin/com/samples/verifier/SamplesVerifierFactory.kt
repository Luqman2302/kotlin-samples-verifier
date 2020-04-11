package com.samples.verifier

import com.samples.verifier.internal.SamplesVerifierInstance

object SamplesVerifierFactory {
    /**
     * @param compilerUrl kotlin-compiler-server url
     * @param kotlinEnv JVM/JS
     */
    @JvmOverloads
    @JvmStatic
    fun create(
        compilerUrl: String = "http://localhost:8080/",
        kotlinEnv: KotlinEnv = KotlinEnv.JVM
    ): SamplesVerifier = SamplesVerifierInstance(compilerUrl, kotlinEnv)
}

enum class KotlinEnv {
    JVM,
    JS
}