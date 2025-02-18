package io.sentry.android.gradle

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.utils.setDisallowChanges
import io.sentry.android.gradle.SentryCliProvider.getSentryCliPath
import io.sentry.android.gradle.SentryPropertiesFileProvider.getPropertiesFilePath
import io.sentry.android.gradle.SentryTasksProvider.capitalized
import io.sentry.android.gradle.SentryTasksProvider.getAssembleTaskProvider
import io.sentry.android.gradle.SentryTasksProvider.getBundleTask
import io.sentry.android.gradle.SentryTasksProvider.getMappingFileProvider
import io.sentry.android.gradle.SentryTasksProvider.getMergeAssetsProvider
import io.sentry.android.gradle.SentryTasksProvider.getPackageBundleTask
import io.sentry.android.gradle.SentryTasksProvider.getPackageProvider
import io.sentry.android.gradle.SentryTasksProvider.getPreBundleTask
import io.sentry.android.gradle.SentryTasksProvider.getTransformerTask
import io.sentry.android.gradle.autoinstall.installDependencies
import io.sentry.android.gradle.extensions.SentryPluginExtension
import io.sentry.android.gradle.instrumentation.SpanAddingClassVisitorFactory
import io.sentry.android.gradle.services.SentrySdkStateHolder
import io.sentry.android.gradle.tasks.SentryGenerateProguardUuidTask
import io.sentry.android.gradle.tasks.SentryUploadNativeSymbolsTask
import io.sentry.android.gradle.tasks.SentryUploadProguardMappingsTask
import io.sentry.android.gradle.transforms.MetaInfStripTransform
import io.sentry.android.gradle.transforms.MetaInfStripTransform.Companion.metaInfStripped
import io.sentry.android.gradle.util.AgpVersions
import io.sentry.android.gradle.util.GroovyCompat
import io.sentry.android.gradle.util.SentryPluginUtils.capitalizeUS
import io.sentry.android.gradle.util.SentryPluginUtils.isMinificationEnabled
import io.sentry.android.gradle.util.SentryPluginUtils.withLogging
import io.sentry.android.gradle.util.detectSentryAndroidSdk
import io.sentry.android.gradle.util.info
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.tasks.StopExecutionException
import org.gradle.api.tasks.TaskProvider
import org.slf4j.LoggerFactory

@Suppress("UnstableApiUsage")
class SentryPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        if (AgpVersions.CURRENT < AgpVersions.VERSION_7_0_0) {
            throw StopExecutionException(
                """
                Using io.sentry.android.gradle:3+ with Android Gradle Plugin < 7 is not supported.
                Either upgrade the AGP version to 7+, or use an earlier version of the Sentry
                Android Gradle Plugin. For more information check our migration guide
                https://docs.sentry.io/platforms/android/migration/#migrating-from-iosentrysentry-android-gradle-plugin-2x-to-iosentrysentry-android-gradle-plugin-300
                """.trimIndent()
            )
        }

        val extension = project.extensions.create(
            "sentry",
            SentryPluginExtension::class.java,
            project
        )
        project.pluginManager.withPlugin("com.android.application") {
            val androidExtension = project.extensions.getByType(AppExtension::class.java)
            val androidComponentsExtension =
                project.extensions.getByType(AndroidComponentsExtension::class.java)
            val cliExecutable = getSentryCliPath(project)

            val extraProperties = project.extensions.getByName("ext")
                as ExtraPropertiesExtension

            val sentryOrgParameter = runCatching {
                extraProperties.get(SENTRY_ORG_PARAMETER).toString()
            }.getOrNull()
            val sentryProjectParameter = runCatching {
                extraProperties.get(SENTRY_PROJECT_PARAMETER).toString()
            }.getOrNull()

            // temp folder for sentry-related stuff
            val tmpDir = File("${project.buildDir}${sep}tmp${sep}sentry")
            tmpDir.mkdirs()

            androidComponentsExtension.onVariants { variant ->
                if (isVariantAllowed(
                        extension,
                        variant.name,
                        variant.flavorName,
                        variant.buildType
                    ) && extension.tracingInstrumentation.enabled.get()
                ) {
                    /**
                     * We detect sentry-android SDK version using configurations.incoming.afterResolve.
                     * This is guaranteed to be executed BEFORE any of the build tasks/transforms are started.
                     *
                     * After detecting the sdk state, we use Gradle's shared build service to persist
                     * the state between builds and also during a single build, because transforms
                     * are run in parallel.
                     */
                    val sdkStateHolderProvider = SentrySdkStateHolder.register(project)
                    project.detectSentryAndroidSdk(
                        "${variant.name}RuntimeClasspath",
                        variant.name,
                        sdkStateHolderProvider
                    )

                    variant.transformClassesWith(
                        SpanAddingClassVisitorFactory::class.java,
                        InstrumentationScope.ALL
                    ) { params ->
                        if (extension.tracingInstrumentation.forceInstrumentDependencies.get()) {
                            params.invalidate.setDisallowChanges(System.currentTimeMillis())
                        }
                        params.debug.setDisallowChanges(
                            extension.tracingInstrumentation.debug.get()
                        )
                        params.features.setDisallowChanges(
                            extension.tracingInstrumentation.features.get()
                        )
                        params.sdkStateHolder.setDisallowChanges(sdkStateHolderProvider)
                        params.tmpDir.set(tmpDir)
                    }
                    variant.setAsmFramesComputationMode(
                        FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
                    )

                    /**
                     * This necessary to address the issue when target app uses a multi-release jar
                     * (MR-JAR) as a dependency. https://github.com/getsentry/sentry-android-gradle-plugin/issues/256
                     *
                     * We register a transform (https://docs.gradle.org/current/userguide/artifact_transforms.html)
                     * that will strip-out unnecessary files from the MR-JAR, so the AGP transforms
                     * will consume corrected artifacts. We only do this when auto-instrumentation is
                     * enabled (otherwise there's no need in this fix) AND when AGP version
                     * is below 7.1.2, where this issue has been fixed.
                     * (https://androidstudio.googleblog.com/2022/02/android-studio-bumblebee-202111-patch-2.html)
                     */
                    if (extension.tracingInstrumentation.enabled.get() &&
                        AgpVersions.CURRENT < AgpVersions.VERSION_7_1_2
                    ) {
                        // we are only interested in runtime configuration (as ASM transform is
                        // also run just for the runtime configuration)
                        project.configurations.named("${variant.name}RuntimeClasspath")
                            .configure {
                                it.attributes.attribute(metaInfStripped, true)
                            }
                        MetaInfStripTransform.register(
                            project.dependencies,
                            extension.tracingInstrumentation.forceInstrumentDependencies.get()
                        )
                    }
                }
            }

            androidExtension.applicationVariants.matching {
                isVariantAllowed(extension, it.name, it.flavorName, it.buildType.name)
            }.configureEach { variant ->
                val bundleTask = withLogging(project.logger, "bundleTask") {
                    getBundleTask(project, variant.name)
                }

                val sentryProperties = getPropertiesFilePath(project, variant)

                val isMinificationEnabled = isMinificationEnabled(
                    project,
                    variant,
                    extension.experimentalGuardsquareSupport.get()
                )
                val isDebuggable = variant.buildType.isDebuggable

                var preBundleTaskProvider: TaskProvider<Task>? = null
                var transformerTaskProvider: TaskProvider<Task>? = null
                var packageBundleTaskProvider: TaskProvider<Task>? = null

                if (isMinificationEnabled) {
                    preBundleTaskProvider = withLogging(project.logger, "preBundleTask") {
                        getPreBundleTask(project, variant.name)
                    }
                    transformerTaskProvider = withLogging(project.logger, "transformerTask") {
                        getTransformerTask(
                            project,
                            variant.name,
                            extension.experimentalGuardsquareSupport.get()
                        )
                    }
                    packageBundleTaskProvider = withLogging(project.logger, "packageBundleTask") {
                        getPackageBundleTask(project, variant.name)
                    }
                } else {
                    project.logger.info {
                        "Minification is not enabled for variant ${variant.name}."
                    }
                }

                val taskSuffix = variant.name.capitalizeUS()

                if (isMinificationEnabled && extension.includeProguardMapping.get()) {
                    // Setup the task to generate a UUID asset file
                    val generateUuidTask = project.tasks.register(
                        "generateSentryProguardUuid$taskSuffix",
                        SentryGenerateProguardUuidTask::class.java
                    ) {
                        it.outputDirectory.set(
                            project.file(
                                File(
                                    project.buildDir,
                                    "generated${sep}assets${sep}sentry${sep}${variant.name}"
                                )
                            )
                        )
                    }
                    getMergeAssetsProvider(variant)?.configure {
                        it.dependsOn(generateUuidTask)
                    }

                    // Setup the task that uploads the proguard mapping and UUIDs
                    val uploadSentryProguardMappingsTask = project.tasks.register(
                        "uploadSentryProguardMappings$taskSuffix",
                        SentryUploadProguardMappingsTask::class.java
                    ) { task ->
                        task.dependsOn(generateUuidTask)
                        task.workingDir(project.rootDir)
                        task.cliExecutable.set(cliExecutable)
                        task.sentryProperties.set(
                            sentryProperties?.let { file -> project.file(file) }
                        )
                        task.uuidDirectory.set(generateUuidTask.flatMap { it.outputDirectory })
                        task.mappingsFiles = getMappingFileProvider(
                            project,
                            variant,
                            extension.experimentalGuardsquareSupport.get()
                        )
                        task.autoUploadProguardMapping.set(extension.autoUploadProguardMapping)
                        task.sentryOrganization.set(sentryOrgParameter)
                        task.sentryProject.set(sentryProjectParameter)
                    }
                    androidExtension.sourceSets.getByName(variant.name).assets.srcDir(
                        generateUuidTask.flatMap { it.outputDirectory }
                    )

                    if (extension.experimentalGuardsquareSupport.get() &&
                        GroovyCompat.isDexguardEnabledForVariant(project, variant.name)
                    ) {
                        // If Dexguard is enabled, we will have to wait for the project to be evaluated
                        // to be able to let the uploadSentryProguardMappings run after them.
                        project.afterEvaluate {
                            project.tasks.named(
                                "dexguardApk${variant.name.capitalized}"
                            ).configure { it.finalizedBy(uploadSentryProguardMappingsTask) }
                            project.tasks.named(
                                "dexguardAab${variant.name.capitalized}"
                            ).configure { it.finalizedBy(uploadSentryProguardMappingsTask) }
                        }
                    } else {
                        // we just hack ourselves into the Proguard/R8 task's doLast.
                        transformerTaskProvider?.configure {
                            it.finalizedBy(uploadSentryProguardMappingsTask)
                        }
                    }

                    // To include proguard uuid file into aab, run before bundle task.
                    preBundleTaskProvider?.configure { task ->
                        task.dependsOn(generateUuidTask)
                    }
                    // The package task will only be executed if the generateUuidTask has already been executed.
                    getPackageProvider(variant)?.configure { task ->
                        task.dependsOn(generateUuidTask)
                    }
                    // App bundle has different package task
                    packageBundleTaskProvider?.configure { task ->
                        task.dependsOn(generateUuidTask)
                    }
                }

                // only debug symbols of non debuggable code should be uploaded (aka release builds).
                // uploadSentryNativeSymbols task will only be executed after the assemble task
                // and also only if `uploadNativeSymbols` is enabled, as this is an opt-in feature.
                if (!isDebuggable && extension.uploadNativeSymbols.get()) {
                    // Setup the task to upload native symbols task after the assembling task
                    val uploadSentryNativeSymbolsTask = project.tasks.register(
                        "uploadSentryNativeSymbolsFor$taskSuffix",
                        SentryUploadNativeSymbolsTask::class.java
                    ) {
                        it.workingDir(project.rootDir)
                        it.buildDir.set(project.buildDir)
                        it.autoUploadNativeSymbol.set(extension.autoUploadNativeSymbols)
                        it.cliExecutable.set(cliExecutable)
                        it.sentryProperties.set(
                            sentryProperties?.let { file -> project.file(file) }
                        )
                        it.includeNativeSources.set(extension.includeNativeSources)
                        it.variantName.set(variant.name)
                        it.sentryOrganization.set(sentryOrgParameter)
                        it.sentryProject.set(sentryProjectParameter)
                    }

                    getAssembleTaskProvider(variant)?.configure {
                        it.finalizedBy(
                            uploadSentryNativeSymbolsTask
                        )
                    }
                    // if its a bundle aab, assemble might not be executed, so we hook into bundle task
                    bundleTask?.configure { it.finalizedBy(uploadSentryNativeSymbolsTask) }
                } else {
                    project.logger.info { "uploadSentryNativeSymbols won't be executed" }
                }
            }

            project.installDependencies(extension)
        }
    }

    private fun isVariantAllowed(
        extension: SentryPluginExtension,
        variantName: String,
        flavorName: String?,
        buildType: String?
    ): Boolean {
        return variantName !in extension.ignoredVariants.get() &&
            flavorName !in extension.ignoredFlavors.get() &&
            buildType !in extension.ignoredBuildTypes.get()
    }

    companion object {
        const val SENTRY_ORG_PARAMETER = "sentryOrg"
        const val SENTRY_PROJECT_PARAMETER = "sentryProject"
        internal const val SENTRY_SDK_VERSION = "5.7.0"

        internal val sep = File.separator

        // a single unified logger used by instrumentation
        internal val logger by lazy {
            LoggerFactory.getLogger(SentryPlugin::class.java)
        }
    }
}
