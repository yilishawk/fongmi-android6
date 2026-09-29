package com.fongmi.gradle

import org.gradle.api.Project

class AbiApkPackaging {

    private static final Map<String, String> ABI_PAIRS = ['arm64-v8a': 'armeabi-v7a', 'armeabi-v7a': 'arm64-v8a'].asImmutable()

    static void configure(Project project, Object apkArtifact) {
        def android = project.extensions.getByName('android')
        def components = project.extensions.getByName('androidComponents')
        def suffix = apkSuffix(project)
        configureAbis(android)
        components.onVariants(components.selector().withBuildType('release')) { variant ->
            def device = configureOutputFileNames(variant, suffix)
            configureFinalizer(project, android, components, variant, apkArtifact)
            configureReleaseExport(project, variant, device)
        }
    }

    /**
     * 产物文件名后缀，直接复用 applicationIdSuffix 用的**同一个参数**（-PcoexistSuffix=.b6）：
     * 一处参数同时决定「包名后缀」和「文件名后缀」，不可能对不上。
     * 输出形态与上一代 webhtv-android6 一致：leanback-armeabi_v7a-b6.apk
     *
     * 上一代把这件事拆成两个来源（CI 输入 app_id_suffix + 环境变量 WEBHTV_APK_SUFFIX），
     * 要靠人工保持同步；这里合成一个，少一个出错点。
     */
    private static String apkSuffix(Project project) {
        def raw = project.findProperty('coexistSuffix')
        if (raw == null) return ''
        def s = raw.toString().replaceAll('[^A-Za-z0-9._-]', '').replaceFirst(/^\./, '')
        return s ? "-${s}" : ''
    }

    static String otherAbi(String abi) {
        return ABI_PAIRS[abi]
    }

    private static void configureAbis(def android) {
        android.splits.abi {
            enable = true
            reset()
            include(*ABI_PAIRS.keySet().toList())
            universalApk = false
        }
    }

    private static String configureOutputFileNames(def variant, String suffix) {
        def flavors = variant.productFlavors.collectEntries { [(it.first): it.second] }
        def device = flavors['device'] ?: 'device'
        variant.outputs.each { output ->
            def abi = output.filters.find { it.filterType.name() == 'ABI' }?.identifier?.replace('-', '_') ?: 'universal'
            output.outputFileName.set("${device}-${abi}${suffix}.apk")
        }
        return device
    }

    private static void configureFinalizer(Project project, def android, def components, def variant, Object apkArtifact) {
        def windows = System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('windows')
        def buildToolsDir = components.sdkComponents.sdkDirectory.get().dir("build-tools/${android.buildToolsVersion}").asFile
        def signingConfig = android.signingConfigs.release
        def finalizeTask = project.tasks.register("finalize${variant.name.capitalize()}Apks", FinalizeApksTask) { task ->
            task.zipalignFile.set(new File(buildToolsDir, windows ? 'zipalign.exe' : 'zipalign'))
            task.apksignerJar.set(new File(buildToolsDir, 'lib/apksigner.jar'))
            task.javaExecutable.set(new File(System.getProperty('java.home'), windows ? 'bin/java.exe' : 'bin/java'))
            task.signingStoreFile.set(signingConfig.storeFile)
            task.keyAlias.set(signingConfig.keyAlias)
            task.storePassword.set(signingConfig.storePassword)
            task.keyPassword.set(signingConfig.keyPassword)
        }
        def request = variant.artifacts.use(finalizeTask)
                .wiredWithDirectories({ task -> task.inputDirectory }, { task -> task.outputDirectory })
                .toTransformMany(apkArtifact)
        finalizeTask.configure { task ->
            task.transformationRequest.set(request)
        }
    }

    private static void configureReleaseExport(Project project, def variant, String device) {
        def taskName = "assemble${variant.name.capitalize()}"
        def apkDirectory = project.layout.buildDirectory.dir("outputs/apk/${device}/release").get().asFile
        project.tasks.matching { it.name == taskName }.configureEach {
            doLast {
                project.copy {
                    from project.fileTree(dir: apkDirectory, include: "${device}-*.apk")
                    into project.rootProject.file('Release/apk')
                    eachFile { it.path = it.name }
                    includeEmptyDirs = false
                }
            }
        }
    }
}
