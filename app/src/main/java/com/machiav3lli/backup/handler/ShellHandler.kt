/*
 * OAndBackupX: open-source apps backup and restore app.
 * Copyright (C) 2020  Antonios Hazim
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.machiav3lli.backup.handler

import android.content.res.AssetManager
import android.os.Environment.DIRECTORY_DOCUMENTS
import com.machiav3lli.backup.BuildConfig
import com.machiav3lli.backup.activities.MainActivityX
import com.machiav3lli.backup.handler.ShellHandler.FileInfo.FileType
import com.machiav3lli.backup.utils.BUFFER_SIZE
import com.machiav3lli.backup.utils.FileUtils.translatePosixPermissionToMode
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuRandomAccessFile
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

class ShellHandler {

    init {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(20)
        )
    }

    private val UTILBOX_NAMES = listOf("toybox", "busybox")
    private val SCRIPTS_SUBDIR = "scripts"
    private val APP_SUBDIR = "OAndBackupX"
    private val VERSION_FILE = "__version__"

    @Throws(ShellCommandFailedException::class)
    fun suGetDirectoryContents(path: File): Array<String> {
        val shellResult = runAsRoot("$utilBoxQuoted ls -bA1 ${quote(path)}")
        return shellResult.out.toTypedArray()
    }

    @Throws(ShellCommandFailedException::class)
    fun suGetDetailedDirectoryContents(
        path: String,
        recursive: Boolean,
        parent: String? = null
    ): List<FileInfo> {
        val shellResult = runAsRoot("$utilBoxQuoted ls -bAll ${quote(path)}")
        val relativeParent = parent ?: ""
        val result = shellResult.out.asSequence()
            .filter { line: String -> line.isNotEmpty() }
            .filter { line: String -> !line.startsWith("total") }
            .filter { line: String -> line.split(Regex("""\s+"""), 0).size > 8 }
            .map { line: String -> FileInfo.fromLsOutput(line, relativeParent, path) }
            .toMutableList()
        if (recursive) {
            val directories = result
                .filter { fileInfo: FileInfo -> fileInfo.fileType == FileType.DIRECTORY }
                .toTypedArray()
            directories.forEach { dir ->
                result.addAll(
                    suGetDetailedDirectoryContents(
                        dir.absolutePath, true,
                        if (parent != null) parent + '/' + dir.filename else dir.filename
                    )
                )
            }
        }
        return result
    }

    /**
     * Uses superuser permissions to retrieve uid, gid and SELinux context of any given directory.
     *
     * @param filepath the filepath to retrieve the information from
     * @return an array with 3 fields: {uid, gid, context}
     */
    @Throws(ShellCommandFailedException::class, UnexpectedCommandResult::class)
    fun suGetOwnerGroupContext(filepath: String): Array<String> {
        // val command = "$utilBoxQuoted stat -c '%u %g %C' ${quote(filepath)}" // %C usually not supported in toybox
        // ls -Z supported as an option since landley/toybox 0.6.0 mid 2015, Android 8 starts mid 2017
        // use -dlZ instead of -dnZ, because -nZ was found (by Kostas!) with an error (with no space between group and context)
        // apparently uid/gid is less tested than names
        var shellResult: Shell.Result? = null
        try {
            val command = "$utilBoxQuoted ls -bdAlZ ${quote(filepath)}"
            shellResult = runAsRoot(command)
            return shellResult.out[0].split(" ", limit = 6).slice(2..4).toTypedArray()
        } catch (e: Throwable) {
            throw UnexpectedCommandResult("'\$command' failed", shellResult)
        }
    }

    @Throws(UtilboxNotAvailableException::class)
    fun setUtilBoxPath(utilBoxName: String) {
        var shellResult = runAsRoot("which $utilBoxName")
        if (shellResult.out.isNotEmpty()) {
            utilBoxPath = shellResult.out.joinToString("")
            if (utilBoxPath.isNotEmpty()) {
                utilBoxQuoted = quote(utilBoxPath)
                shellResult = runAsRoot("$utilBoxQuoted --version")
                if (shellResult.out.isNotEmpty()) {
                    val utilBoxVersion = shellResult.out.joinToString("")
                    Timber.i("Using Utilbox $utilBoxName : $utilBoxQuoted : $utilBoxVersion")
                }
                return
            }
        }
        // not found => try bare executables (no utilbox prefixed)
        utilBoxPath = ""
        utilBoxQuoted = ""
    }

    class ShellCommandFailedException(
        @field:Transient val shellResult: Shell.Result,
        val commands: Array<out String>
    ) : Exception()

    class UnexpectedCommandResult(message: String, val shellResult: Shell.Result?) :
        Exception(message)

    class UtilboxNotAvailableException(val triedBinaries: String, cause: Throwable?) :
        Exception(cause)

    class FileInfo(
        /**
         * Returns the filepath, relative to the original location
         *
         * @return relative filepath
         */
        val filePath: String,
        val fileType: FileType,
        absoluteParent: String,
        val owner: String,
        val group: String,
        var fileMode: Int,
        var fileSize: Long,
        var fileModTime: Date
    ) {
        enum class FileType {
            REGULAR_FILE, BLOCK_DEVICE, CHAR_DEVICE, DIRECTORY, SYMBOLIC_LINK, NAMED_PIPE, SOCKET
        }

        val absolutePath: String = absoluteParent + '/' + File(filePath).name

        //val fileMode = fileMode
        //val fileSize = fileSize
        //val fileModTime = fileModTime
        var linkName: String? = null
            private set
        val filename: String
            get() = File(filePath).name

        override fun toString(): String {
            return "FileInfo{" +
                    "filePath='" + filePath + '\'' +
                    ", fileType=" + fileType +
                    ", owner=" + owner +
                    ", group=" + group +
                    ", fileMode=" + fileMode.toString(8) +
                    ", fileSize=" + fileSize +
                    ", fileModTime=" + fileModTime +
                    ", absolutePath='" + absolutePath + '\'' +
                    ", linkName='" + linkName + '\'' +
                    '}'
        }

        companion object {
            private val PATTERN_LINKSPLIT       = Pattern.compile(" -> ")
            private val FALLBACK_MODE_FOR_DIR   = translatePosixPermissionToMode("rwxrwx--x")
            private val FALLBACK_MODE_FOR_FILE  = translatePosixPermissionToMode("rw-rw----")
            private val FALLBACK_MODE_FOR_CACHE = translatePosixPermissionToMode("rwxrws--x")

            /*  from toybox ls.c

                  for (i = 0; i<len; i++) {
                    *to++ = '\\';
                    if (strchr(TT.escmore, from[i])) *to++ = from[i];
                    else if (-1 != (j = stridx("\\\a\b\033\f\n\r\t\v", from[i])))
                      *to++ = "\\abefnrtv"[j];
                    else to += sprintf(to, "%03o", from[i]);
                  }
            */

            fun unescapeLsOutput(str : String) : String {
                val is_escaped = Regex("""\\([\\abefnrtv ]|\d\d\d)""")
                return str.replace(
                            is_escaped
                        ) { match: MatchResult ->
                            val matched = match.groups[1]?.value ?: "?" // "?" cannot happen because it matched
                            when (matched) {
                                """\""" -> """\"""
                                "a" -> "\u0007"
                                "b" -> "\u0008"
                                "e" -> "\u001b"
                                "f" -> "\u000c"
                                "n" -> "\u000a"
                                "r" -> "\u000d"
                                "t" -> "\u0009"
                                "v" -> "\u000b"
                                " " -> " "
                                else -> (((matched[0].digitToInt() * 8) + matched[1].digitToInt()) * 8 + matched[2].digitToInt()).toChar().toString()
                            }
                        }
            }

            /**
             * Create an instance of FileInfo from a line of ls output
             *
             * @param lsLine single output line from ls -bAll
             * @return an instance of FileInfo
             */
            fun fromLsOutput(
                lsLine: String,
                parentPath: String?,
                absoluteParent: String
            ): FileInfo {
                // Expecting something like this (with whitespace) from
                // ls -bAll /data/data/com.shazam.android/
                // field   0     1    2       3            4       5            6             7     8
                //   "drwxrwx--x 5 u0_a441 u0_a441       4096 2021-10-19 01:54:32.029625295 +0200 files"
                // links have 2 additional fields:
                //   "lrwxrwxrwx 1 root    root            61 2021-08-25 16:44:49.757000571 +0200 lib -> /data/app/com.shazam.android-I4tzgPt3Ay6mFgz4Jnb4dg==/lib/arm"
                // [0] Filemode, [1] number of directories/links inside, [2] owner [3] group [4] size
                // [5] mdate, [6] mtime, [7] mtimezone, [8] filename
                //var absoluteParent = absoluteParent
                var parent = absoluteParent
                val tokens = lsLine.split(Regex("""\s+"""), 9).toTypedArray()
                var filePath: String?
                val owner = tokens[2]
                val group = tokens[3]
                // 2020-11-26 04:35:21.543772855 +0100
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.getDefault())
                val fileModTime =
                    formatter.parse("${tokens[5]} ${tokens[6].split(".")[0]} ${tokens[7]}")
                // If ls was executed with a file as parameter, the full path is echoed. This is not
                // good for processing. Removing the absolute parent and setting the parent to be the parent
                // and not the file itself
                if (tokens[8].startsWith(parent)) {
                    parent = File(parent).parent!!
                    tokens[8] = tokens[8].substring(parent.length + 1)
                }
                val fileName = unescapeLsOutput(tokens[8])
                filePath =
                    if (parentPath == null || parentPath.isEmpty()) {
                        fileName
                    } else {
                        "${parentPath}/${fileName}"
                    }
                var fileMode = FALLBACK_MODE_FOR_FILE
                try {
                    fileMode = translatePosixPermissionToMode(tokens[0].substring(1))
                } catch (e: IllegalArgumentException) {
                    // Happens on cache and code_cache dir because of sticky bits
                    // drwxrws--x 2 u0_a108 u0_a108_cache 4096 2020-09-22 17:36 cache
                    // drwxrws--x 2 u0_a108 u0_a108_cache 4096 2020-09-22 17:36 code_cache
                    // These will be filtered out later, so don't print a warning here
                    // Downside: For all other directories with these names, the warning is also hidden
                    // This can be problematic for system packages, but for apps these bits do not
                    // make any sense.
                    if (filePath == "cache" || filePath == "code_cache") {
                        // Fall back to the known value of these directories
                        fileMode = FALLBACK_MODE_FOR_CACHE
                    } else {
                        fileMode =
                            if (tokens[0][0] == 'd') {
                                FALLBACK_MODE_FOR_DIR
                            } else {
                                FALLBACK_MODE_FOR_FILE
                            }
                        Timber.w(
                            String.format(
                                "Found a file with special mode (%s), which is not processable. Falling back to %s. filepath=%s ; absoluteParent=%s",
                                tokens[0], fileMode, filePath, parent
                            )
                        )
                    }
                } catch (e: Throwable) {
                    LogsHandler.unhandledException(e, filePath)
                }
                var linkName: String? = null
                var fileSize: Long = 0
                val type: FileType
                when (tokens[0][0]) {
                    'd' -> type = FileType.DIRECTORY
                    'l' -> {
                        type = FileType.SYMBOLIC_LINK
                        val nameAndLink = PATTERN_LINKSPLIT.split(filePath as CharSequence)
                        filePath = nameAndLink[0]
                        linkName = nameAndLink[1]
                    }
                    'p' -> type = FileType.NAMED_PIPE
                    's' -> type = FileType.SOCKET
                    'b' -> type = FileType.BLOCK_DEVICE
                    'c' -> type = FileType.CHAR_DEVICE
                    else -> {
                        type = FileType.REGULAR_FILE
                        fileSize = tokens[4].toLong()
                    }
                }
                val result = FileInfo(
                    filePath!!,
                    type,
                    parent,
                    owner,
                    group,
                    fileMode,
                    fileSize,
                    fileModTime!!
                )
                result.linkName = linkName
                return result
            }

            fun fromLsOutput(lsLine: String, absoluteParent: String): FileInfo {
                return fromLsOutput(lsLine, "", absoluteParent)
            }
        }
    }

    companion object {

        var utilBoxPath = ""
            private set
        var utilBoxQuoted = ""
            private set
        lateinit var scriptDir : File
            private set
        var scriptUserDir : File? = null
            private set

        interface RunnableShellCommand {
            fun runCommand(vararg commands: String?): Shell.Job
        }

        class ShRunnableShellCommand : RunnableShellCommand {
            override fun runCommand(vararg commands: String?): Shell.Job {
                return Shell.sh(*commands)
            }
        }

        class SuRunnableShellCommand : RunnableShellCommand {
            override fun runCommand(vararg commands: String?): Shell.Job {
                return Shell.su(*commands)
            }
        }

        @Throws(ShellCommandFailedException::class)
        private fun runShellCommand(
            shell: RunnableShellCommand,
            vararg commands: String
        ): Shell.Result {
            // defining stdout and stderr on our own
            // otherwise we would have to set set the flag redirect stderr to stdout:
            // Shell.Config.setFlags(Shell.FLAG_REDIRECT_STDERR);
            // stderr is used for logging, so it's better not to call an application that does that
            // and keeps quiet
            Timber.d("Running Command: ${commands.joinToString(" ; ")}")
            val stdout: List<String> = arrayListOf()
            val stderr: List<String> = arrayListOf()
            val result = shell.runCommand(*commands).to(stdout, stderr).exec()
            Timber.d("Command(s) ${commands.joinToString(" ; ")} ended with ${result.code}")
            if (!result.isSuccess)
                throw ShellCommandFailedException(result, commands)
            return result
        }

        @Throws(ShellCommandFailedException::class)
        fun runAsUser(vararg commands: String): Shell.Result {
            return runShellCommand(ShRunnableShellCommand(), *commands)
        }

        @Throws(ShellCommandFailedException::class)
        fun runAsRoot(vararg commands: String): Shell.Result {
            return runShellCommand(SuRunnableShellCommand(), *commands)
        }

        // the Android command line shell is mksh
        // mksh quoting
        //   '...'  single quotes would do well, but single quotes cannot be used
        //   $'...' dollar + single quotes need many escapes
        //   "..."  needs only a few escaped chars (backslash, dollar, double quote, back tick)
        //   from mksh man page:
        //      double quote quotes all characters,
        //          except '$' , '`' and '\' ,
        //          up to the next unquoted double quote
        val charactersToBeEscaped =
            Regex("""[\\${'$'}"`]""")   // blacklist, only escape those that are necessary

        fun quote(parameter: String): String {
            return "\"${parameter.replace(charactersToBeEscaped) { "\\${it.value}" }}\""
            //return "\"${parameter.replace(charactersToBeEscaped) { "\\${(0xFF and it.value[0].code).toString(8).padStart(3, '0')}" }}\""
        }

        fun quote(parameter: File): String {
            return quote(parameter.absolutePath)
        }

        fun quoteMultiple(parameters: Collection<String>): String =
            parameters.joinToString(" ", transform = ::quote)

        fun isFileNotFoundException(ex: ShellCommandFailedException): Boolean {
            val err = ex.shellResult.err
            return err.isNotEmpty() && err[0].contains("no such file or directory", true)
        }

        @Throws(IOException::class)
        fun quirkLibsuReadFileWorkaround(inputFile: FileInfo, output: OutputStream) {
            quirkLibsuReadFileWorkaround(inputFile.absolutePath, inputFile.fileSize, output)
        }

        @Throws(IOException::class)
        fun quirkLibsuReadFileWorkaround(filepath: String, filesize: Long, output: OutputStream) {
            val maxRetries: Short = 10
            var stream = SuRandomAccessFile.open(filepath, "r")
            val buf = ByteArray(BUFFER_SIZE)
            var readOverall: Long = 0
            var retriesLeft = maxRetries.toInt()
            while (true) {
                val read = stream.read(buf)
                if (0 > read && filesize > readOverall) {
                    // For some reason, SuFileInputStream throws eof much to early on slightly bigger files
                    // This workaround detects the unfinished file like the tar archive does (it tracks
                    // the written amount of bytes, too because it needs to match the header)
                    // As side effect the archives slightly differ in size because of the flushing mechanism.
                    if (0 >= retriesLeft) {
                        Timber.e(
                            String.format(
                                "Could not recover after %d tries. Seems like there is a bigger issue. Maybe the file has changed?",
                                maxRetries
                            )
                        )
                        throw IOException(
                            String.format(
                                "Could not read expected amount of input bytes %d; stopped after %d tries at %d",
                                filesize, maxRetries, readOverall
                            )
                        )
                    }
                    Timber.w(
                        String.format(
                            "SuFileInputStream EOF before expected after %d bytes (%d are missing). Trying to recover. %d retries lef",
                            readOverall, filesize - readOverall, retriesLeft
                        )
                    )
                    // Reopen the file to reset eof flag
                    stream.close()
                    stream = SuRandomAccessFile.open(filepath, "r")
                    stream.seek(readOverall)
                    // Reduce the retries
                    retriesLeft--
                    continue
                }
                if (0 > read) {
                    break
                }
                output.write(buf, 0, read)
                readOverall += read.toLong()
                // successful write, resetting retries
                retriesLeft = maxRetries.toInt()
            }
        }

        fun findScript(script : String) : File? {
            var found : File? = null
            scriptUserDir?.let {
                found = File(it, script)
            }
            if( ! (found?.isFile ?: false) )
                found = File(scriptDir, script)
            return found
        }
    }

    init {
        val names = UTILBOX_NAMES
        names.any {
            try {
                setUtilBoxPath(it)
                true
            } catch (e: UtilboxNotAvailableException) {
                Timber.d("Tried utilbox name '${it}'. Not available.")
                false
            }
        }
        if (utilBoxQuoted.isEmpty()) {
            Timber.d("No more options for utilbox. Bailing out.")
            throw UtilboxNotAvailableException(names.joinToString(", "), null)
        }

        // copy scripts to file storage
        val context = MainActivityX.context
        scriptUserDir = File(context.getExternalFilesDir(DIRECTORY_DOCUMENTS), APP_SUBDIR + "/" + SCRIPTS_SUBDIR)
        scriptDir = File(context.filesDir, SCRIPTS_SUBDIR)
        // don't copy if the files exist and are from the current app version
        val appVersion = BuildConfig.VERSION_NAME
        val version = try { File(scriptDir, VERSION_FILE).readText() } catch(e : Throwable) { "" }
        if(version != appVersion) {
            try {
                context.assets.copyRecursively("scripts", scriptDir)
                File(scriptDir, VERSION_FILE).writeText(appVersion)
            } catch(e: Throwable) {
                Timber.w("cannot copy scripts to ${scriptDir}")
            }
        }
    }
}

fun AssetManager.copyRecursively(assetPath: String, targetFile: File) {
    list(assetPath)?.let { list ->
        if (list.isEmpty()) { // assetPath is file
            open(assetPath).use { input ->
                FileOutputStream(targetFile.absolutePath).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

        } else { // assetPath is folder
            targetFile.deleteRecursively()
            targetFile.mkdir()

            list.forEach {
                copyRecursively("$assetPath/$it", File(targetFile, it))
            }
        }
    }
}
