import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

val localProperties =
    Properties().apply {
      val file = rootDir.resolve("local.properties")
      if (file.exists()) file.inputStream().use { load(it) }
    }

fun local(key: String, default: String = ""): String = localProperties.getProperty(key, default)

android {
  namespace = "com.larplens"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.larplens"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1"

    // Phones and Apple Silicon emulators are arm64; skipping the other ABIs cuts OpenCV/ML Kit/
    // Meta native libs out of the APK
    ndk { abiFilters += "arm64-v8a" }

    // Developer Mode: leave these as "0". For production, use Wearables Developer Center values.
    manifestPlaceholders["mwdat_application_id"] = local("mwdat_application_id", "0")
    manifestPlaceholders["mwdat_client_token"] = local("mwdat_client_token", "0")

    // DEV ONLY: compiles the ElevenLabs key into the APK. Move TTS behind a backend before sharing
    // a build with anyone.
    buildConfigField("String", "ELEVENLABS_API_KEY", "\"${local("elevenlabs_api_key")}\"")
    // DEV ONLY, same caveat: the Anthropic key used by the Claude name tag reader
    buildConfigField("String", "ANTHROPIC_API_KEY", "\"${local("anthropic_api_key")}\"")
    buildConfigField(
        "String",
        "ELEVENLABS_VOICE_ID",
        "\"${local("elevenlabs_voice_id", "cCYjmrGZaI86GUJ7F2Nn")}\"",
    )
    buildConfigField(
        "String",
        "ELEVENLABS_MODEL_ID",
        "\"${local("elevenlabs_model_id", "eleven_flash_v2_5")}\"",
    )
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging {
    resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // OpenCV and the Meta SDK (via fbjni) both bundle the C++ runtime; package one copy
    jniLibs { pickFirsts += "**/libc++_shared.so" }
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material3)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.mockdevice)
  // On-device OCR (bundled Latin model, works offline)
  implementation(libs.mlkit.text.recognition)
  // Claude vision fallback for handwriting
  implementation(libs.anthropic.java)
  // Name tag detection (contours, minAreaRect, morphology)
  implementation(libs.opencv)

  testImplementation(libs.junit)
  testImplementation(libs.opencv.desktop)
}

// ---- Guest list: bundle ONLY name + point1..3 from the CSV (no LinkedIn/website/Luma URLs) ----

/**
 * Reads the `name` column and any `point1`..`point3` columns of a guest CSV and writes
 * `guests.tsv`: one guest per line, `name<TAB>point1<TAB>point2<TAB>point3`. Every other column
 * is dropped so it never ships in the app.
 */
abstract class GuestNamesTask : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val csv: ConfigurableFileCollection

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun run() {
    val out = outputDir.get().asFile.apply {
      deleteRecursively()
      mkdirs()
    }
    val source = csv.files.firstOrNull { it.isFile }
    val guests = source?.let { guestsFrom(it.readText(Charsets.UTF_8).removePrefix("﻿")) }.orEmpty()
    if (source == null) logger.warn("Guest CSV not found; the app will have an empty guest list")
    else logger.lifecycle("Guest list: ${guests.size} guests from ${source.name}")
    File(out, "guests.tsv").writeText(guests.joinToString("\n") { it.joinToString("\t") })
  }

  private fun guestsFrom(text: String): List<List<String>> {
    val rows = parseCsv(text)
    val header = rows.firstOrNull()?.map { it.trim().lowercase() }.orEmpty()
    val nameColumn = header.indexOf("name")
    require(nameColumn >= 0) { "Guest CSV has no 'name' column" }
    val pointColumns = listOf("point1", "point2", "point3").map { header.indexOf(it) }
    fun clean(value: String?) = value.orEmpty().replace(Regex("[\\t\\r\\n]+"), " ").trim()
    return rows.drop(1).mapNotNull { row ->
      val name = clean(row.getOrNull(nameColumn)).takeIf(String::isNotEmpty) ?: return@mapNotNull null
      listOf(name) + pointColumns.map { col -> if (col < 0) "" else clean(row.getOrNull(col)) }
    }
  }

  /** Minimal RFC 4180 parser: quoted fields, escaped quotes, commas/newlines inside quotes. */
  private fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
      val c = text[i]
      when {
        quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
        c == '"' -> quoted = !quoted
        !quoted && c == ',' -> { row += field.toString(); field.clear() }
        !quoted && (c == '\n' || c == '\r') -> {
          if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
          row += field.toString(); field.clear()
          if (row.any { it.isNotBlank() }) rows += row
          row = mutableListOf()
        }
        else -> field.append(c)
      }
      i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
      row += field.toString()
      if (row.any { it.isNotBlank() }) rows += row
    }
    return rows
  }
}

val generateGuestNames =
    tasks.register<GuestNamesTask>("generateGuestNames") {
      // First file that exists wins: the list with facts, else the names-only export
      csv.from(
          rootDir.resolve(local("guest_csv", "larphacks-guests.csv")),
          rootDir.resolve("LarpHacks guests CSV.csv"),
      )
    }

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(generateGuestNames, GuestNamesTask::outputDir)
  }
}
