/*
 * digdown is a third-party PaperMC plugin for Minecraft Java Edition
 * that forces the warden to tick even in non-simulated chunks.
 *
 * Copyright (C) 2026 VidTu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// Plugins.
plugins {
    id("java")
}

// Language.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

// Metadata.
group = "ru.vidtu.digdown"
base.archivesName = "digdown"
description = "Speed up your world loading by removing unneeded chunks."

// Repositories for dependencies.
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") // Paper.
}

// Dependencies.
dependencies {
    // Annotations.
    compileOnly(libs.jspecify)
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.error.prone.annotations)

    // Paper.
    compileOnly(libs.paper.api)
}

tasks.withType<JavaCompile> {
    // Compile with UTF-8.
    options.encoding = "UTF-8"

    // Set the Java 25 target.
    options.release = 25
}

tasks.withType<ProcessResources> {
    // Filter with UTF-8.
    filteringCharset = "UTF-8"

    // Expand version.
    inputs.property("version", version)

    // Replace properties.
    filesMatching("paper-plugin.yml") {
        expand(inputs.properties)
    }
}

// Add LICENSE and NOTICE.
tasks.withType<Jar> {
    from("LICENSE")
    from("NOTICE")
}
