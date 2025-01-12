/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    // uncomment for publishing task
    `maven-publish`
    // uncomment for signing the jars during publishing task
    signing
    kotlin("jvm") version "1.9.20"
    kotlin("plugin.serialization") version "1.9.0"
 //   id("org.jetbrains.kotlinx.dataframe") version "0.11.0"
    id("org.jetbrains.dokka") version "1.9.20"
}
group = "io.github.rossetti"
version = "R1.1.8"

repositories {

    mavenCentral()
}

dependencies {

    // https://mvnrepository.com/artifact/io.github.microutils/kotlin-logging-jvm
    api(group = "io.github.oshai", name = "kotlin-logging-jvm", version = "7.0.3")

    api(group = "org.slf4j", name = "slf4j-api", version = "2.0.16")

    // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
    implementation(group = "ch.qos.logback", name = "logback-classic", version = "1.5.15")
    // https://mvnrepository.com/artifact/ch.qos.logback/logback-core
    implementation(group = "ch.qos.logback", name = "logback-core", version = "1.5.15")

    // this is needed because POI uses log4j internally and SXSSFWorkbook() causes a logging that isn't captured
// https://mvnrepository.com/artifact/org.apache.logging.log4j/log4j-to-slf4j
//    implementation("org.apache.logging.log4j:log4j-to-slf4j:2.23.1")
    implementation("org.apache.logging.log4j:log4j-to-slf4j:2.24.3")

    api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
//    implementation("org.jetbrains.kotlin:kotlin-reflect:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.6.3")

    // https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-kotlin-jvm
    api("org.jetbrains.lets-plot:lets-plot-kotlin-jvm:4.7.2")
    // https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-batik
    implementation("org.jetbrains.lets-plot:lets-plot-batik:4.3.3")
    // https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-kotlin-kernel
//    implementation("org.jetbrains.lets-plot:lets-plot-kotlin-kernel:4.7.3")

// https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-common
//    implementation("org.jetbrains.lets-plot:lets-plot-common:4.0.0")

    // https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-image-export
    api("org.jetbrains.lets-plot:lets-plot-image-export:4.3.2")

    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/dataframe-core
//    api("org.jetbrains.kotlinx:dataframe-core:0.12.0")
    api("org.jetbrains.kotlinx:dataframe-core:0.13.1")

//    implementation("org.junit.jupiter:junit-jupiter:5.9.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.0")

    // https://mvnrepository.com/artifact/org.ktorm/ktorm-core
//    implementation("org.ktorm:ktorm-core:3.5.0")

 //   implementation(group = "org.apache.commons", name = "commons-math3", version = "3.6.1")
    // replacement for apache math commons
// https://mvnrepository.com/artifact/org.hipparchus/hipparchus-core
    api("org.hipparchus:hipparchus-core:3.1")
// https://mvnrepository.com/artifact/org.hipparchus/hipparchus-stat
    api("org.hipparchus:hipparchus-stat:3.1")

    implementation("com.google.guava:guava:33.2.1-jre")

    // https://mvnrepository.com/artifact/org.knowm.xchart/xchart
//    implementation("org.knowm.xchart:xchart:3.8.2")
    
    // https://mvnrepository.com/artifact/com.opencsv/opencsv
    //Dependency maven:commons-collections:commons-collections:3.2.2 is vulnerable Cx78f40514-81ff,  Score: 7.5
    //This requires opencsv to update their dependency
//    implementation("com.opencsv:opencsv:5.9") //TODO vulnerability not showing on maven

    // https://mvnrepository.com/artifact/org.apache.commons/commons-csv
    // not needed because kotlinx:dataframe-core has this library as api
//    implementation("org.apache.commons:commons-csv:1.11.0")

    // https://db.apache.org/derby/releases
    // 10.16.1.1 is only compatible with Java 17
    // upgrade to Java 21 and 10.17.1.0 or higher to avoid this vulnerability.
    implementation(group = "org.apache.derby", name = "derby", version = "10.15.2.0")
    implementation(group = "org.apache.derby", name = "derbyshared", version = "10.15.2.0")
    implementation(group = "org.apache.derby", name = "derbyclient", version = "10.15.2.0")
    implementation(group = "org.apache.derby", name = "derbytools", version = "10.15.2.0")

    implementation(group = "org.postgresql", name = "postgresql", version = "42.7.3")

    implementation(group = "org.xerial", name = "sqlite-jdbc", version = "3.46.0.0")

    // https://mvnrepository.com/artifact/org.duckdb/duckdb_jdbc
    implementation("org.duckdb:duckdb_jdbc:1.1.3")
    implementation(group = "com.zaxxer", name = "HikariCP", version = "5.1.0")

    // https://mvnrepository.com/artifact/org.dhatim/fastexcel-reader
//    implementation("org.dhatim:fastexcel-reader:0.14.0")
    // https://mvnrepository.com/artifact/org.dhatim/fastexcel
//    implementation("org.dhatim:fastexcel:0.14.0")

    // https://mvnrepository.com/artifact/org.apache.poi/poi
    api(group = "org.apache.poi", name = "poi", version = "5.2.5")
    // https://mvnrepository.com/artifact/org.apache.poi/poi-ooxml
    implementation(group = "org.apache.poi", name = "poi-ooxml", version = "5.2.5")// this vulnerability is not on maven
    // required POI to update their dependencies to remove the vulnerability

    implementation(kotlin("stdlib-jdk8"))
// https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-test
//    api("org.jetbrains.kotlin:kotlin-test:1.9.24")

}

//tasks.test {
//    useJUnitPlatform()
//}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "17"
}

// this is supposed to exclude the logback.xml resource file from the generated jar
// this is good because user can then provide their own logging specification
// TODO need reference to why this is good
tasks.jar {
//    manifest {
//        attributes(
//                "Implementation-Title" to project.name,
//                "Implementation-Version" to project.version
//        )
//    }
    exclude("logback.xml")
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "17"
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "17"
}

// these extensions are needed when publishing to maven
// because maven requires javadoc jar, sources jar, and the build jar
// these jars are placed in build/libs by default
java {
    // comment this out to not make jar file with javadocs during normal build
    withJavadocJar()
    // comment this out to not make jar file with source during normal build
    withSourcesJar()
}

// run the publishing task to generate the signed jars required for maven central
// jars will be found in build/JSL/releases or build/JSL/snapshots
publishing {
    publications {
        create<MavenPublication>("KSLCore") {
            groupId = "io.github.rossetti"
            artifactId = "KSLCore"
            // update this field when generating new release
            version = "R1.1.8"
            from(components["java"])
            versionMapping {
                usage("java-api") {
                    fromResolutionOf("runtimeClasspath")
                }
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }
            pom {
                name.set("KSLCore")
                description.set("The KSL, an open source kotlin library for simulation")
                url.set("https://github.com/rossetti/KSL")
                licenses {
                    license {
                        name.set("GPL, Version 3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("rossetti")
                        name.set("Manuel D. Rossetti")
                        email.set("rossetti@uark.edu")
                    }
                }
                scm {
                    connection.set("https://github.com/rossetti/KSL.git")
                    developerConnection.set("git@github.com:rossetti/KSL.git")
                    url.set("https://github.com/rossetti/KSL")
                }
            }
        }
    }
    repositories {
        maven {
            // change URLs to point to your repos, e.g. http://my.org/repo
            // this publishes to local folder within build directory
            // avoids having to log into maven, etc., but requires manual upload of releases
            val releasesRepoUrl = uri(layout.buildDirectory.dir("KSL/releases"))
            val snapshotsRepoUrl = uri(layout.buildDirectory.dir("KSL/snapshots"))
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
        }
    }
}

// signing requires config information in folder user home directory
// .gradle/gradle.properties. To publish jars without signing, just comment out
signing {
    sign(publishing.publications["KSLCore"])
}
