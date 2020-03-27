package com.samples.verifier.internal

import com.samples.verifier.*
import com.samples.verifier.internal.utils.RequestHelper
import com.samples.verifier.internal.utils.cloneRepository
import com.samples.verifier.internal.utils.processFile
import com.samples.verifier.model.ExecutionResult
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class SamplesVerifierInstance(compilerUrl: String, compilerType: CompilerType) : SamplesVerifier {
    private val logger = LoggerFactory.getLogger("Samples Verifier")
    private val requestHelper = RequestHelper(compilerUrl, compilerType, logger)

    override fun collect(url: String, attributes: List<String>, type: FileType): Map<ExecutionResult, Code> {
        check(url, attributes, type)
        return requestHelper.results
    }

    override fun check(url: String, attributes: List<String>, type: FileType) {
        val dir = File(url.substringAfterLast('/').substringBeforeLast('.'))
        try {
            logger.info("Cloning repository...")
            cloneRepository(dir, url)
            processFiles(dir, attributes, type)
        } catch (e: GitException) {
            //TODO
            logger.error("${e.message}\n")
        } catch (e: CallException) {
            //TODO
        } catch (e: IOException) {
            //TODO
            logger.error("${e.message}\n")
        } finally {
            if (dir.isDirectory) {
                FileUtils.deleteDirectory(dir)
            } else {
                dir.delete()
            }
        }
    }

    private fun processFiles(directory: File, attributes: List<String>, type: FileType) {
        Files.walk(directory.toPath()).use {
            it.forEach { path: Path ->
                val file = path.toFile()
                when (type) {
                    FileType.MD -> {
                        if (file.extension == "md") {
                            logger.info("Processing ${file}...")
                            processFile(file, type, attributes, requestHelper)
                        }
                    }
                    FileType.HTML -> {
                        if (file.extension == "html") {
                            logger.info("Processing ${file}...")
                            processFile(file, type, attributes, requestHelper)
                        }
                    }
                }
            }
        }
    }
}