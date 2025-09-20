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

plugins {
    `java-library`
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("org.jetbrains.dokka") version "2.0.0"
    id("com.vanniktech.maven.publish") version "0.33.0"
}

group = "io.github.rossetti"
version = "R1.2.4"

repositories {

    mavenCentral()
}

dependencies {

    // https://mvnrepository.com/artifact/io.github.microutils/kotlin-logging-jvm
    api(group = "io.github.oshai", name = "kotlin-logging-jvm", version = "7.0.7")

    api(group = "org.slf4j", name = "slf4j-api", version = "2.0.17")

    // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
    implementation(group = "ch.qos.logback", name = "logback-classic", version = "1.5.18")
    // https://mvnrepository.com/artifact/ch.qos.logback/logback-core
    implementation(group = "ch.qos.logback", name = "logback-core", version = "1.5.18")

    // this is needed because POI uses log4j internally and SXSSFWorkbook() causes a logging that isn't captured
// https://mvnrepository.com/artifact/org.apache.logging.log4j/log4j-to-slf4j
//    implementation("org.apache.logging.log4j:log4j-to-slf4j:2.23.1")
    implementation("org.apache.logging.log4j:log4j-to-slf4j:2.25.0")

    api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1") // 0.7.0 has code breaking changes
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
//    implementation("org.jetbrains.kotlin:kotlin-reflect:1.8.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.9.0")

    // https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-kotlin-jvm
    api("org.jetbrains.lets-plot:lets-plot-kotlin-jvm:4.9.3")
    // https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-batik
    implementation("org.jetbrains.lets-plot:lets-plot-batik:4.5.2")
    // https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-kotlin-kernel
//    implementation("org.jetbrains.lets-plot:lets-plot-kotlin-kernel:4.7.3")

// https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-common
//    implementation("org.jetbrains.lets-plot:lets-plot-common:4.0.0")

    // https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-image-export
    api("org.jetbrains.lets-plot:lets-plot-image-export:4.5.1")

    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/dataframe-core
//    api("org.jetbrains.kotlinx:dataframe-core:0.15.0")
    api("org.jetbrains.kotlinx:dataframe:1.0.0-Beta2")

//    implementation("org.junit.jupiter:junit-jupiter:5.9.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.0")

    // https://mvnrepository.com/artifact/org.ktorm/ktorm-core
//    implementation("org.ktorm:ktorm-core:3.5.0")

// https://mvnrepository.com/artifact/org.hipparchus/hipparchus-core
    api("org.hipparchus:hipparchus-core:4.0.1")
// https://mvnrepository.com/artifact/org.hipparchus/hipparchus-stat
    api("org.hipparchus:hipparchus-stat:4.0.1")

//    implementation("com.google.guava:guava:33.4.8-jre")

    // https://db.apache.org/derby/releases
    implementation(group = "org.apache.derby", name = "derby", version = "10.17.1.0")
    implementation(group = "org.apache.derby", name = "derbyshared", version = "10.17.1.0")
    implementation(group = "org.apache.derby", name = "derbyclient", version = "10.17.1.0")
    implementation(group = "org.apache.derby", name = "derbytools", version = "10.17.1.0")

    implementation(group = "org.postgresql", name = "postgresql", version = "42.7.7")

    implementation(group = "org.xerial", name = "sqlite-jdbc", version = "3.50.2.0")

    // https://mvnrepository.com/artifact/org.duckdb/duckdb_jdbc
    implementation("org.duckdb:duckdb_jdbc:1.3.1.0")
    implementation(group = "com.zaxxer", name = "HikariCP", version = "6.3.0")

    // https://mvnrepository.com/artifact/org.dhatim/fastexcel-reader
//    implementation("org.dhatim:fastexcel-reader:0.14.0")
    // https://mvnrepository.com/artifact/org.dhatim/fastexcel
//    implementation("org.dhatim:fastexcel:0.14.0")

    // https://mvnrepository.com/artifact/org.apache.poi/poi
    api(group = "org.apache.poi", name = "poi", version = "5.4.1")
    // https://mvnrepository.com/artifact/org.apache.poi/poi-ooxml
    implementation(group = "org.apache.poi", name = "poi-ooxml", version = "5.4.1")
    // required POI to update their dependencies to remove the vulnerability

//    implementation(kotlin("stdlib-jdk8"))
// https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-test
//    api("org.jetbrains.kotlin:kotlin-test:1.9.24")

}

// this is supposed to exclude the logback.xml resource file from the generated jar
// this is good because the user can then provide their own logging specification
tasks.jar {
    exclude("logback.xml")
}

kotlin {
    jvmToolchain(21)
 //   explicitApiWarning()
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "KSLCore", version.toString())

    pom {
        name = "KSLCore"
        description = "The KSL, an open source kotlin library for simulation."
        inceptionYear = "2023"
        url = "https://github.com/rossetti/KSL"
        licenses {
            license {
                name = "GPL, Version 3.0"
                url = "https://www.gnu.org/licenses/gpl-3.0.txt"
                distribution = "https://www.gnu.org/licenses/gpl-3.0.txt"
            }
        }
        developers {
            developer {
                id = "rossetti"
                name = "Manuel D. Rossetti"
                email = "rossetti@uark.edu"
                url = "https://github.com/rossetti"
            }
        }
        scm {
            url = "https://github.com/rossetti/KSL"
            connection = "scm:git:git://github.com/rossetti/KSL.git"
            developerConnection = "scm:git:ssh://git@github.com/rossetti/KSL.git"
        }
    }
}

// build.gradle.kts

dokka {

    dokkaPublications.html {
        suppressInheritedMembers.set(true)
        failOnWarning.set(true)
    }

    dokkaSourceSets.main {
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/rossetti/KSL/tree/main/KSLCore/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}