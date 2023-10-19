/* Copyright 2023 ENGEL Austria GmbH */
package io.github.woolph.gradle.dependencycheck

import io.github.woolph.gradle.Skipable
import io.github.woolph.gradle.dependencycheck.suppression.CheckSuppressionFileTask
import io.github.woolph.gradle.dependencycheck.suppression.GenerateSuppressionFileTask
import io.github.woolph.gradle.dependencycheck.suppression.PrintVulnerabilityCauseTask
import io.github.woolph.gradle.dependencycheck.suppression.UpdateSuppressionFileTask
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.provider.Providers
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.delegateClosureOf
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.named
import java.io.File
import javax.inject.Inject

abstract class DependencyCheckExtension @Inject constructor(project: Project) : Skipable {
    override val skip: Property<Boolean> = project.objects.property(Boolean::class.java)
        .convention(false)

    /**
     * defines the threshold for the CVS score. Dependencies with a vulnerability with a CVS score greater or equal to
     * the cvssThreshold will cause the build to fail. The maximum value for CVS scores is 10.0, therefore a
     * cvssThreshold of greater than 10.0 effectively allows al vulnerabilities to pass the check.
     * Defaults to 0.0
     */
    val cvssThreshold: Property<Double> = project.objects.property(Double::class.java)
        .convention(0.0)

    /**
     * defines whether the cause of a vulnerability should be printed to the console.
     */
    val printVulnerabilityCauseEnabled: Property<Boolean> = project.objects.property(Boolean::class.java)
        .convention(false)

    /**
     * location of the dependencyCheck suppression file.
     * Defaults to "$projectDir/dependency-check-suppression.xml"
     */
    val suppressionFile: RegularFileProperty = project.objects.fileProperty()
        .convention(project.layout.projectDirectory.file("dependency-check-suppression.xml"))

    companion object {
        internal fun Project.getParentDirectoryOf(file: Provider<RegularFile>): Provider<Directory> = file.map {
            layout.projectDirectory.asFile.toPath().relativize(it.asFile.parentFile.toPath()).toString()
        }.map {
            if (it.isNotEmpty()) {
                layout.projectDirectory.dir(it)
            } else {
                layout.projectDirectory
            }
        }

        internal fun Project.makeSibling(file: RegularFileProperty, fileNamePattern: (File) -> String): Provider<RegularFile> =
            getParentDirectoryOf(file).zip(file) { parent, actualFile -> parent.file(fileNamePattern(actualFile.asFile)) }

        internal fun <T> Provider<T>.filter(predicate: (T) -> Boolean): Provider<T> = flatMap {
            if (predicate(it)) {
                this
            } else {
                Providers.notDefined()
            }
        }

        internal fun Provider<RegularFile>.filterExists(): Provider<RegularFile> = filter { it.asFile.exists() }

        internal fun Project.applyDependencyCheckExtension(baseExtension: ExtensionAware) {
            val thisExtension = baseExtension.extensions.create("dependencyCheck", DependencyCheckExtension::class, project)

            try {
                val check = tasks.named("check")

                plugins.apply("org.owasp.dependencycheck")

                val checkSuppressionFileTask = tasks.create<CheckSuppressionFileTask>("checkSuppressionFile") {
                    originalSuppressionFile.convention(thisExtension.suppressionFile)
                    onlyIf { thisExtension.suppressionFile.asFile.get().exists() }
                }

                tasks.create<GenerateSuppressionFileTask>("generateSuppressionFile") {
                    originalSuppressionFile.convention(thisExtension.suppressionFile.filterExists())
                    suppressionFile.convention(makeSibling(thisExtension.suppressionFile) { it.nameWithoutExtension + ".new." + it.extension })
                }

                tasks.create<UpdateSuppressionFileTask>("updateSuppressionFile") {
                    originalSuppressionFile.convention(thisExtension.suppressionFile.filterExists())
                    suppressionFile.convention(makeSibling(thisExtension.suppressionFile) { it.nameWithoutExtension + ".new." + it.extension })
                }

                val printVulnerabilityCause = tasks.create<PrintVulnerabilityCauseTask>("printVulnerabilityCause") {
                    onlyIf("printVulnerabilityCauseEnabled is true") { thisExtension.printVulnerabilityCauseEnabled.get() }
                }

                listOf(
                    "dependencyCheckAggregate",
                    "dependencyCheckPurge",
                    "dependencyCheckUpdate",
                ).forEach { taskName ->
                    tasks.named(taskName) {
                        group = "verification/dependency-check"
                    }
                }

                val dependencyCheckAnalyze = tasks.named<org.owasp.dependencycheck.gradle.tasks.Analyze>("dependencyCheckAnalyze") {
                    group = "verification/dependency-check"
                    onlyIf {
                        thisExtension.skip.map { !it }.get()
                    }
                    dependsOn(checkSuppressionFileTask)

                    finalizedBy(printVulnerabilityCause)
                }

                check {
                    dependsOn(dependencyCheckAnalyze)
                }

                afterEvaluate {
                    if (thisExtension.skip.get()) {
                        logger.warn("dependencyCheck is disabled!")
                    }

                    extensions.getByName<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension>("dependencyCheck").apply {
                        skip = thisExtension.skip.get()
                        failBuildOnCVSS = thisExtension.cvssThreshold.get().toFloat()
                        formats = listOf(
                            org.owasp.dependencycheck.reporting.ReportGenerator.Format.HTML,
                            org.owasp.dependencycheck.reporting.ReportGenerator.Format.JUNIT,
                        )
                        if (thisExtension.suppressionFile.get().asFile.exists()) {
                            logger.warn("dependencyCheck suppression file ${thisExtension.suppressionFile.get()} is being applied")
                            suppressionFile = thisExtension.suppressionFile.get().asFile.toString()
                        }

                        scanConfigurations = configurations.filter {
                            !it.name.startsWith("test") &&
                                it.name.contains("DependenciesMetadata") && (
                                    it.name.startsWith("api") ||
                                        it.name.startsWith("implementation") ||
                                        it.name.startsWith("runtimeOnly") ||
                                        it.name.contains("Api") ||
                                        it.name.contains("Implementation") ||
                                        it.name.contains("RuntimeOnly")
                                    )
                        }.map {
                            it.name
                        }

                        if (project.hasProperty("DEPENDENCY_CHECK_DB_CONNECTION")) {
                            logger.warn("dependencyCheck uses ${project.properties["DEPENDENCY_CHECK_DB_CONNECTION"]} instead of default in-mem-db (=> autoUpdate is deactivated!)")
                            autoUpdate = false

                            data(
                                delegateClosureOf<groovy.lang.GroovyObject> {
                                    setProperty("driver", project.properties["DEPENDENCY_CHECK_DB_DRIVER"])
                                    setProperty("connectionString", project.properties["DEPENDENCY_CHECK_DB_CONNECTION"])
                                    if (project.hasProperty("DEPENDENCY_CHECK_DB_USER")) {
                                        setProperty("username", project.properties["DEPENDENCY_CHECK_DB_USER"])
                                    }
                                    if (project.hasProperty("DEPENDENCY_CHECK_DB_PASSWORD")) {
                                        setProperty("password", project.properties["DEPENDENCY_CHECK_DB_PASSWORD"])
                                    }
                                },
                            )
                        } else {
                            logger.warn("dependencyCheck using default settings for data")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("dependencyCheck will not be applied due to exception", e)
            }
        }
    }
}
