/* Copyright 2023 ENGEL Austria GmbH */
package io.github.woolph.gradle.sonar

import io.github.woolph.gradle.Skipable
import javax.inject.Inject
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.property

abstract class SonarQubeExtension @Inject constructor(project: Project) : Skipable {
    override val skip: Property<Boolean> = project.objects.property<Boolean>().convention(false)

    /**
     * defines the edition of the sonarqube server (currently, this information is used to skip the
     * sonarqube analyzis on Community edition servers for Pull Request builds, because pull request
     * analyzis is not supported by the Community edition)
     *
     * This property can also be set by calling the gradle build with the following argument
     * -Psonarqube.edition={edition}. It is set to UNKNOWN by default.
     */
    val edition: Property<SonarQubeEdition> =
        project.objects
            .property<SonarQubeEdition>()
            .convention(
                project.providers
                    .gradleProperty("sonarqube.edition")
                    .map { SonarQubeEdition.of(it) ?: SonarQubeEdition.UNKNOWN }
                    .orElse(SonarQubeEdition.UNKNOWN),
            )

    companion object {
        internal fun Project.applySonarQubeExtension(baseExtension: ExtensionAware) {
            val thisExtension =
                baseExtension.extensions.create("sonarQube", SonarQubeExtension::class, project)

            try {
                val check = tasks.named("check")
                val test = tasks.named<org.gradle.api.tasks.testing.Test>("test")

                plugins.apply("jacoco")

                val jacocoTestReport =
                    tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport")

                plugins.apply("org.sonarqube")

                val sonarTask = tasks.findByName("sonar") as? org.sonarqube.gradle.SonarTask
                sonarTask?.apply {
                    onlyIf { thisExtension.skip.map { !it }.get() }
                    dependsOn(jacocoTestReport)
                }
                //                val sonarTask =
                // tasks.getByName<org.sonarqube.gradle.SonarTask>("sonar") {
                //                    onlyIf {
                //                        thisExtension.skip.map { !it }.get()
                //                    }
                //                    dependsOn(jacocoTestReport)
                //                }

                jacocoTestReport {
                    dependsOn(test)

                    reports {
                        html.required.set(true)
                        xml.required.set(true)
                    }
                }

                check {
                    dependsOn(jacocoTestReport)
                    sonarTask?.let { dependsOn(it) }
                }

                afterEvaluate {
                    if (thisExtension.skip.get()) {
                        logger.warn("sonarqube is disabled!")
                    } else if (thisExtension.edition.get() == SonarQubeEdition.COMMUNITY &&
                        System.getenv("BUILD_REASON") == "PullRequest") {
                        logger.warn(
                            "sonarqube is running on Community Edition and build reason is PullRequest => skipping sonarqube!")
                        thisExtension.skip.set(true)
                    }

                    extensions.getByName<org.sonarqube.gradle.SonarExtension>("sonar").apply {
                        isSkipProject = thisExtension.skip.get()

                        properties {
                            property("sonar.projectKey", project.name)
                            property("sonar.jacoco.reportPaths", "build/jacoco/test.exec")
                            property("sonar.qualitygate.wait", true)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("sonarqube will not be applied due to exception", e)
            }
        }
    }
}
