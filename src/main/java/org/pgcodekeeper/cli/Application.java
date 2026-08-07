/*******************************************************************************
 * Copyright 2017-2026 TAXTELECOM, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright 2006 StartNet s.r.o.
 *
 * Distributed under MIT license
 *******************************************************************************/
package org.pgcodekeeper.cli;

import org.kohsuke.args4j.CmdLineException;
import org.pgcodekeeper.cli.localizations.Messages;
import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.utils.FileUtils;
import org.pgcodekeeper.core.utils.UnixPrintWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Compares two PostgreSQL dumps and outputs information about differences in
 * the database schemas.
 *
 * @author fordfrog
 */
public final class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = process(args) ? 0 : 1;
        } catch (Throwable th) {
            // Keep the CLI boundary fail-closed for Errors not handled by process().
            exitCode = 1;
            reportFatalError(th);
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Formats the single-line stderr report for a Throwable that reached the
     * top of the CLI, including Errors like {@link OutOfMemoryError}.
     */
    static String formatFatalError(Throwable th) {
        return "pgcodekeeper-cli: fatal error: " + th;
    }

    private static void reportFatalError(Throwable th) {
        try {
            System.err.println(formatFatalError(th));
            th.printStackTrace(System.err);
        } catch (Throwable ignored) {
            // reporting must never mask the non-zero exit code
        }
    }

    /**
     * @return success value
     */
    static boolean process(String[] args) {

        CliArgs arguments = new CliArgs();
        try {
            if (!arguments.parse(args)) {
                return true;
            }
            logCacheMode(arguments);
            if (arguments.isClearLibCache()) {
                clearCache();
            }

            return switch (arguments.getMode()) {
                case PARSE -> parse(arguments);
                case GRAPH -> graph(arguments);
                case BATCH -> batchDiff(arguments);
                default -> {
                    if (arguments.getOldSrc() == null || arguments.getNewSrc() == null) {
                        // clear cache
                        yield true;
                    }
                    yield diff(arguments);
                }
            };
        } catch (CmdLineException ex) {
            writeError(ex.getLocalizedMessage());
            return false;
        } catch (Exception e) {
            if (arguments.isDebug()) {
                LOG.error(Messages.Main_log_running_error, e);
                e.printStackTrace(System.err);
            } else {
                writeError(e.getLocalizedMessage());
                writeError(Messages.Main_show_stacktrace);
            }

            return false;
        }
    }

    /**
     * Reports the resolved PostgreSQL cache configuration before any work
     * starts. A silently degraded run, such as a persistent cache whose
     * directory never reached the settings, is otherwise indistinguishable
     * from a correct one in a CI log.
     */
    private static void logCacheMode(CliArgs arguments) {
        String cacheDir = arguments.getPgCatalogCacheDir();
        LOG.info(Messages.Main_log_cache_mode.formatted(
                cacheDir != null ? cacheDir : Messages.Main_log_cache_dir_absent,
                arguments.getPgCatalogCacheMaxMb(),
                arguments.isPgCatalogCacheRows(),
                arguments.isPgRoutineBodyHashFirst()));
    }

    private static boolean diff(CliArgs arguments)
            throws InterruptedException, IOException, SQLException {
        try (PrintWriter encodedWriter = getDiffWriter(arguments)) {
            var diff = new PgDiffCli(arguments);
            String text;
            try {
                LOG.info(Messages.Main_log_create_script);
                text = diff.createDiff();
            } catch (IllegalStateException ex) {
                printError(diff, ex);
                return false;
            }

            if (arguments.isSafeMode()) {
                Set<DangerStatement> dangerTypes = PgCodeKeeperApi.checkDangerousStatements(
                        arguments.getProvider(), "CLI", text, arguments, arguments.getAllowedDangers());

                if (!dangerTypes.isEmpty()) {
                    String dangerStmt = dangerTypes.stream().map(DangerStatement::name)
                            .collect(Collectors.joining(", ")); //$NON-NLS-1$
                    var logMsg = Messages.Main_log_contains_dangerous_statements.formatted(dangerStmt);
                    LOG.warn(logMsg);
                    String msg = Messages.Main_danger_statements.formatted(dangerStmt);
                    writeToConsole(msg);
                    if (encodedWriter != null) {
                        encodedWriter.println("-- " + msg); //$NON-NLS-1$
                    }
                    return false;
                }
            }

            if (encodedWriter != null) {
                encodedWriter.println(text);
            }

            String url = arguments.getRunOnDb();
            if (arguments.isRunOnTarget() || url != null) {
                if (url == null) {
                    url = arguments.getOldSrc();
                }

                LOG.info(Messages.Main_log_apply_migration_script);
                PgCodeKeeperApi.runSQL(arguments.getProvider(), "CLI", text, url, arguments);
            } else if (encodedWriter == null) {
                writeToConsole(text);
            }
        }

        LOG.info(Messages.Main_log_succes_finish);
        return true;
    }

    private static boolean batchDiff(CliArgs arguments)
            throws CmdLineException, InterruptedException, IOException {
        var batch = new PgBatchDiffCli(arguments);
        try {
            return batch.run();
        } catch (IllegalStateException ex) {
            printError(batch.getErrors(), ex);
            return false;
        }
    }

    private static PrintWriter getDiffWriter(CliArgs arguments)
            throws FileNotFoundException, UnsupportedEncodingException {
        String outFile = arguments.getOutputTarget();
        return outFile == null ? null : new UnixPrintWriter(outFile, arguments.getOutCharsetName());
    }

    private static boolean parse(CliArgs arguments) throws IOException, InterruptedException {
        PgDiffCli diff = new PgDiffCli(arguments);
        try {
            if (arguments.isProjUpdate()) {
                LOG.info(Messages.Main_log_start_update_proj);
                diff.updateProject();
            } else {
                LOG.info(Messages.Main_log_start_export_proj);
                diff.exportProject();
            }
        } catch (IllegalStateException ex) {
            printError(diff, ex);
            return false;
        }

        LOG.info(Messages.Main_log_succes_finish);
        return true;
    }

    private static boolean graph(CliArgs arguments) throws IOException, InterruptedException {
        var diff = new PgDiffCli(arguments);
        List<String> dependencies;
        // the try covers the load, not just the construction of its loader:
        // picking a loader for the source format never reads anything, so a
        // catch around that alone could never see a load error. The load
        // happens inside analyzeDependencies, and its errors land in the same
        // list every other mode gates on
        try {
            dependencies = diff.analyzeDependencies();
        } catch (IllegalStateException ex) {
            printError(diff, ex);
            return false;
        }

        try (PrintWriter pw = getDiffWriter(arguments)) {
            Consumer<String> consumer = pw != null ? pw::println : Application::writeToConsole;
            dependencies.forEach(consumer);
        }

        LOG.info(Messages.Main_log_succes_finish);
        return true;
    }

    private static void clearCache() throws IOException {
        Path metaPath = Utils.getMetaPath();
        FileUtils.deleteRecursive(metaPath);
        writeMessage(Messages.Main_cach_clear);
    }

    private static void printError(PgDiffCli diff, Throwable cause) {
        printError(diff.getErrors(), cause);
    }

    static void printError(List<Object> errors, Throwable cause) {
        for (var err : errors) {
            writeError(err);
        }
        // never fail silently. The collected loader errors account for exactly
        // one failure - the load that reported them, which is what
        // LoadErrorsException stands for and why it adds nothing here. Every
        // other cause is a failure the list cannot explain: a catalog reader
        // that died, a wrapped OutOfMemoryError, an invariant a loader tripped
        // over. Dropping it because a parse error happened to be in the list
        // leaves the operator fixing a syntax error that was never the reason,
        // and the caller returns false straight after, so nothing downstream
        // ever sees the cause either
        if (errors.isEmpty() || !(cause instanceof LoadErrorsException)) {
            writeError(cause);
        }
    }

    private static void writeToConsole(String message) {
        System.out.println(message);
    }

    private static void writeMessage(Object message) {
        String msg = message.toString();
        LOG.info(msg);
        writeToConsole(msg);
    }

    private static void writeError(Object message) {
        String msg = message.toString();
        LOG.error(msg);
        System.err.println(msg);
    }

    private Application() {
    }
}
