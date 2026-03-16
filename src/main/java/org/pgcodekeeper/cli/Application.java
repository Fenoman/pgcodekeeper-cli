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
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.settings.DiffSettings;
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
        boolean result = process(args);
        if (!result) {
            System.exit(1);
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
            if (arguments.isClearLibCache()) {
                clearCache();
            }

            return switch (arguments.getMode()) {
                case PARSE -> parse(arguments);
                case GRAPH -> graph(arguments);
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

    private static boolean diff(CliArgs arguments)
            throws InterruptedException, IOException, SQLException {
        try (PrintWriter encodedWriter = getDiffWriter(arguments)) {
            var diffSettings = new DiffSettings(arguments);
            var diff = new PgDiffCli(arguments, diffSettings);
            String text;
            try {
                LOG.info(Messages.Main_log_create_script);
                text = diff.createDiff();
            } catch (IllegalStateException ex) {
                printError(diff);
                return false;
            }

            if (arguments.isSafeMode()) {
                Set<DangerStatement> dangerTypes = PgCodeKeeperApi.checkDangerousStatements(
                        arguments.getProvider(), "CLI", text, diffSettings, arguments.getAllowedDangers());

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
                PgCodeKeeperApi.runSQL(arguments.getProvider(), "CLI", text, url, diffSettings);
            } else if (encodedWriter == null) {
                writeToConsole(text);
            }
        }

        LOG.info(Messages.Main_log_succes_finish);
        return true;
    }

    private static PrintWriter getDiffWriter(CliArgs arguments)
            throws FileNotFoundException, UnsupportedEncodingException {
        String outFile = arguments.getOutputTarget();
        return outFile == null ? null : new UnixPrintWriter(outFile, arguments.getOutCharsetName());
    }

    private static boolean parse(CliArgs arguments) throws IOException, InterruptedException {
        PgDiffCli diff = new PgDiffCli(arguments, new DiffSettings(arguments));
        try {
            if (arguments.isProjUpdate()) {
                LOG.info(Messages.Main_log_start_update_proj);
                diff.updateProject();
            } else {
                LOG.info(Messages.Main_log_start_export_proj);
                diff.exportProject();
            }
        } catch (IllegalStateException ex) {
            diff.getErrors().forEach(Application::writeError);
            return false;
        }

        LOG.info(Messages.Main_log_succes_finish);
        return true;
    }

    private static boolean graph(CliArgs arguments) throws IOException, InterruptedException {
        var diff = new PgDiffCli(arguments, new DiffSettings(arguments));
        ILoader dbLoader;
        try {
            dbLoader = diff.getDatabaseLoader(arguments.getNewSrc(),
                    arguments.getTargetLibXmls(), arguments.getTargetLibs(), arguments.getTargetLibsWithoutPriv());
        } catch (IllegalStateException ex) {
            printError(diff);
            return false;
        }
        LOG.info(Messages.Main_log_build_graph_deps);
        List<String> dependencies = PgCodeKeeperApi.analyzeDependencies(dbLoader, arguments.getGraphNames(),
                arguments.getGraphDepth(), arguments.isGraphReverse(),
                arguments.getGraphFilterTypes(), arguments.isGraphInvertFilter());

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

    private static void printError(PgDiffCli diff) {
        for (var err : diff.getErrors()) {
            writeError(err);
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
