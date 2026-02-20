package com.modcompiler.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompilerScreen()
            }
        }
    }
}

@Composable
fun CompilerScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("Select a mod zip to compile.") }
    var workingDir by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                status = "Copying zip..."
                val cacheZip = File(ctx.cacheDir, "mod.zip")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheZip).use { output ->
                        input.copyTo(output)
                    }
                }
                status = "Unzipping..."
                val targetDir = File(ctx.cacheDir, "mod-${System.currentTimeMillis()}")
                unzip(cacheZip, targetDir)
                workingDir = targetDir
                status = "Ready: ${targetDir.absolutePath}. Tap Compile."
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(status)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("application/zip")) }) { Text("Select .zip") }
            Button(onClick = {
                val dir = workingDir
                if (dir == null) {
                    status = "Pick a zip first"
                    return@Button
                }
                scope.launch(Dispatchers.IO) {
                    status = "Running ./gradlew build..."
                    val output = runBuild(dir)
                    status = output
                }
            }) { Text("Compile") }
        }
    }
}

private fun unzip(zipFile: File, targetDir: File) {
    targetDir.mkdirs()
    ZipInputStream(zipFile.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            val outFile = File(targetDir, entry.name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { zis.copyTo(it) }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
}

private fun runBuild(dir: File): String {
    // Find gradlew anywhere inside the unzipped tree (common when archive has a top-level folder).
    val gradlew = dir.walkTopDown()
        .filter { it.isFile && it.name == "gradlew" }
        .firstOrNull()
        ?: return "gradlew not found in extracted folder. Contents: " +
            dir.list()?.joinToString(", ").orEmpty()

    gradlew.setExecutable(true)
    val workDir = gradlew.parentFile ?: dir
    val cmd = "cd \"${workDir.absolutePath}\" && ./gradlew build"

    val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
        .redirectErrorStream(true)
        .directory(workDir)

    return try {
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        "Exit ${proc.exitValue()}\n$output"
    } catch (e: Exception) {
        "error: ${e.message}"
    }
}
