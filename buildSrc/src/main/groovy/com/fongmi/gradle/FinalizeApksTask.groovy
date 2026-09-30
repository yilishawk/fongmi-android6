package com.fongmi.gradle

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

import javax.inject.Inject
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface FinalizeApkParameters extends WorkParameters {

    RegularFileProperty getInputApk()
    RegularFileProperty getOutputApk()
    RegularFileProperty getZipalignFile()
    RegularFileProperty getApksignerJar()
    RegularFileProperty getJavaExecutable()
    RegularFileProperty getSigningStoreFile()
    Property<String> getAbi()
    Property<String> getKeyAlias()
    Property<String> getStorePassword()
    Property<String> getKeyPassword()
}

abstract class FinalizeApkWorkAction implements WorkAction<FinalizeApkParameters> {

    private final ExecOperations execOperations

    @Inject
    FinalizeApkWorkAction(ExecOperations execOperations) {
        this.execOperations = execOperations
    }

    @Override
    void execute() {
        def abi = parameters.abi.get()
        def removeAbi = AbiApkPackaging.otherAbi(abi)
        if (!removeAbi) throw new GradleException("Unsupported ABI ${abi}")
        def inputApk = parameters.inputApk.get().asFile
        def outputApk = parameters.outputApk.get().asFile
        def filtered = new File(outputApk.parentFile, "${outputApk.name}.filtered")
        def aligned = new File(outputApk.parentFile, "${outputApk.name}.aligned")
        def signed = new File(outputApk.parentFile, "${outputApk.name}.signed")
        try {
            outputApk.parentFile.mkdirs()
            Files.deleteIfExists(outputApk.toPath())
            filterChaquopyAssets(inputApk, filtered, abi, removeAbi)
            align(filtered, aligned)
            sign(aligned, signed)
            Files.move(signed.toPath(), outputApk.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (Throwable t) {
            dumpFailure(t, abi, inputApk, outputApk, filtered, aligned, signed)
            throw t
        } finally {
            Files.deleteIfExists(filtered.toPath())
            Files.deleteIfExists(aligned.toPath())
            Files.deleteIfExists(signed.toPath())
        }
    }

    /**
     * 失败现场 dump —— 重抛之前先把现场打到 stderr。
     *
     * 为什么必须有这个：本 work 是通过 AGP 的 artifact transform 挂上去的
     * （见 AbiApkPackaging.configureFinalizer 里的
     *   variant.artifacts.use(finalizeTask).wiredWithDirectories(...).toTransformMany(APK)）。
     * 一旦这里抛异常，AGP 的 PackageAndroidArtifact$IncrementalSplitterRunnable 会把它
     * 包装成一个**空异常**：Gradle 的 "What went wrong" 里只剩
     *
     *     > A failure occurred while executing
     *       com.android.build.gradle.tasks.PackageAndroidArtifact$IncrementalSplitterRunnable
     *
     * —— 既没有 message、也没有 Caused by，连 --stacktrace 都拿不到线索。
     * 2026-09-30 run 36674838783 实测就是这个形态，当时完全无法定位。
     * 而且同一份代码在 run 36665462723 / 36676451105 上又能成功 ⇒ 这是个**间歇性**失败，
     * 更需要在它真的发生时留下现场。
     *
     * 刻意捕获 Throwable 而不是 Exception：目标里也可能是 Error（OOM 之类）。
     */
    private static void dumpFailure(Throwable t, String abi, File inputApk, File outputApk,
                                    File filtered, File aligned, File signed) {
        def out = System.err
        def size = { File f -> f.exists() ? f.length() : -1L }
        def mb = { long b -> "${(b / 1024L / 1024L)} MB" }
        out.println("========== FINALIZE APK FAILURE ==========")
        out.println("abi         = ${abi}")
        out.println("inputApk    = ${inputApk} exists=${inputApk.exists()} size=${size(inputApk)}")
        out.println("outputApk   = ${outputApk} parentExists=${outputApk.parentFile.exists()}")
        out.println("filtered    = ${filtered} exists=${filtered.exists()} size=${size(filtered)}")
        out.println("aligned     = ${aligned} exists=${aligned.exists()} size=${size(aligned)}")
        out.println("signed      = ${signed} exists=${signed.exists()} size=${size(signed)}")
        def dir = outputApk.parentFile
        out.println("usableSpace = ${dir.usableSpace} (${mb(dir.usableSpace)}) @ ${dir}")
        out.println("heap        = free ${mb(Runtime.runtime.freeMemory())} / max ${mb(Runtime.runtime.maxMemory())}")
        out.println("throwable   = ${t.getClass().getName()}: ${t.message}")
        out.println("--- stack trace ---")
        t.printStackTrace(out)
        out.println("--- cause chain ---")
        def c = t.cause
        def depth = 1
        while (c != null) {
            out.println("  [${depth}] ${c.getClass().getName()}: ${c.message}")
            c = c.cause
            depth++
        }
        out.println("========== END FINALIZE APK FAILURE ==========")
        out.flush()
    }

    private static void filterChaquopyAssets(File inputApk, File filtered, String abi, String removeAbi) {
        def sawChaquopy = false
        def removedRequirements = false
        def removedStdlib = false
        def removedNative = 0
        def keptRequirements = false
        def keptStdlib = false
        def keptNative = 0
        def inputZip = ZipFile.builder().setFile(inputApk).get()
        try {
            def outputZip = new ZipArchiveOutputStream(filtered)
            try {
                def entries = inputZip.entries
                while (entries.hasMoreElements()) {
                    def entry = entries.nextElement()
                    // Android 6 (API 23) 分支把 Chaquopy 关掉了（settings.gradle 里
                    // include ':chaquo' 被注释、app/build.gradle 的 :chaquo 依赖也被注释），
                    // 所以 APK 里**根本没有** assets/chaquopy/（实测条目数 = 0）。
                    // 因此先记录"这个 APK 到底有没有 Chaquopy 资产"，下面的完整性断言
                    // 只在真的有的时候才生效 —— 否则它会无条件抛异常，
                    // 让本分支的 release 包永远打不出来。
                    // （2026-09-29 CI run 36532625030：两个 ABI 都报 Incomplete Chaquopy ABI assets。）
                    // 注意这行必须在下面所有 continue 之前。
                    if (entry.name.startsWith("assets/chaquopy/")) sawChaquopy = true
                    if (entry.name == "assets/chaquopy/requirements-${removeAbi}.imy") {
                        removedRequirements = true
                        continue
                    }
                    if (entry.name == "assets/chaquopy/stdlib-${removeAbi}.imy") {
                        removedStdlib = true
                        continue
                    }
                    if (entry.name.startsWith("assets/chaquopy/bootstrap-native/${removeAbi}/")) {
                        removedNative++
                        continue
                    }
                    if (entry.name == "assets/chaquopy/requirements-${abi}.imy") keptRequirements = true
                    if (entry.name == "assets/chaquopy/stdlib-${abi}.imy") keptStdlib = true
                    if (entry.name.startsWith("assets/chaquopy/bootstrap-native/${abi}/")) keptNative++
                    def rawInput = inputZip.getRawInputStream(entry)
                    try {
                        outputZip.addRawArchiveEntry(new ZipArchiveEntry(entry), rawInput)
                    } finally {
                        rawInput.close()
                    }
                }
            } finally {
                outputZip.close()
            }
        } finally {
            inputZip.close()
        }
        // 只有"APK 里确实带 Chaquopy 资产"时才要求它齐全。
        // 不带 ⇒ 本分支的正常形态，直接放行（后续照常 zipalign + 签名）。
        if (sawChaquopy && (!removedRequirements || !removedStdlib || removedNative == 0 || !keptRequirements || !keptStdlib || keptNative == 0)) {
            throw new GradleException("Incomplete Chaquopy ABI assets for ${abi} in ${inputApk.name}")
        }
    }

    private void align(File inputApk, File outputApk) {
        execOperations.exec {
            commandLine parameters.zipalignFile.get().asFile.absolutePath, '-P', '16', '-f', '4', inputApk.absolutePath, outputApk.absolutePath
            // 诊断用：exec 的输出默认走 Gradle 的 INFO 级日志，不开 --info 就完全看不到
            // zipalign 自己打的报错（zip 结构异常、内存不足……）。
            // 直接转发到进程 stderr ⇒ 无论日志级别都可见，而且不必把整个构建开到 --info
            // （--info 对 8 分钟的构建输出量很大，有撑爆 CI 单 job 日志上限的风险）。
            errorOutput = System.err
        }
    }

    private void sign(File inputApk, File outputApk) {
        execOperations.exec {
            environment 'APK_KS_PASS', parameters.storePassword.get()
            environment 'APK_KEY_PASS', parameters.keyPassword.get()
            // ⚠ v1 (JAR signing) 必须开启，否则包**装不上 Android 6**。
            //
            // 依据（AOSP 官方文档 "APK signature scheme v2"）：
            //   "APK signature scheme v2 was introduced in Android 7.0 (Nougat).
            //    To make a APK installable on Android 6.0 (Marshmallow) and older devices,
            //    the APK should be signed using JAR signing before being signed with the v2 scheme."
            //   "Older platforms ignore v2 signatures and only verify v1 signatures."
            //
            // 这里原本是 v1=false / v2=true —— 那是 TV-fongmi 上游的配置（上游 minSdk = 24，
            // v2-only 没问题）。**本分支把 minSdk 降到 23**，所以必须补上 v1。
            //
            // 实测（2026-09-29）：
            //   CI 的 release 包 v1=false  ⇒ apksigner 报 DOES NOT VERIFY / Missing META-INF/MANIFEST.MF
            //   本机 debug 包（AGP 签）    ⇒ v1=true + v2=true ⇒ Verifies
            //   上一代 webhtv-android6 发布包 ⇒ v1=true + v2=true ⇒ Verifies
            //
            // 注意顺序：v1 必须在 zipalign **之后**做（见 execute() 里的 align → sign），
            // 否则 zipalign 会破坏 v1 签名。
            commandLine parameters.javaExecutable.get().asFile.absolutePath,
                    '-jar', parameters.apksignerJar.get().asFile.absolutePath,
                    'sign', '--ks', parameters.signingStoreFile.get().asFile.absolutePath,
                    '--ks-key-alias', parameters.keyAlias.get(), '--ks-pass', 'env:APK_KS_PASS',
                    '--key-pass', 'env:APK_KEY_PASS', '--v1-signing-enabled', 'true',
                    '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'false',
                    '--v4-signing-enabled', 'false', '--out', outputApk.absolutePath, inputApk.absolutePath
            // 同上：apksigner 的报错也直接转 stderr，免得必须开 --info 才看得见。
            errorOutput = System.err
        }
    }
}

abstract class FinalizeApksTask extends DefaultTask {

    private final WorkerExecutor workerExecutor

    @Inject
    FinalizeApksTask(WorkerExecutor workerExecutor) {
        this.workerExecutor = workerExecutor
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getInputDirectory()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getZipalignFile()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getApksignerJar()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getJavaExecutable()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getSigningStoreFile()

    @Input
    abstract Property<String> getKeyAlias()

    @Internal
    abstract Property<String> getStorePassword()

    @Internal
    abstract Property<String> getKeyPassword()

    @Internal
    abstract Property<Object> getTransformationRequest()

    @TaskAction
    void finalizeApks() {
        transformationRequest.get().submit(this, workerExecutor.noIsolation(), FinalizeApkWorkAction) { artifact, outputLocation, params ->
            def abi = artifact.filters.find { it.filterType.name() == 'ABI' }?.identifier
            if (!abi) throw new GradleException("No ABI filter for ${artifact.outputFile}")
            def inputFile = new File(artifact.outputFile)
            params.inputApk.set(inputFile)
            params.outputApk.set(new File(outputLocation.asFile, inputFile.name))
            params.zipalignFile.set(zipalignFile)
            params.apksignerJar.set(apksignerJar)
            params.javaExecutable.set(javaExecutable)
            params.signingStoreFile.set(signingStoreFile)
            params.abi.set(abi)
            params.keyAlias.set(keyAlias)
            params.storePassword.set(storePassword)
            params.keyPassword.set(keyPassword)
            params.outputApk.get().asFile
        }
    }
}
