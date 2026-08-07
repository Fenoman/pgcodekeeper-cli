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
 *******************************************************************************/
package org.pgcodekeeper.cli;

import org.kohsuke.args4j.CmdLineException;
import org.pgcodekeeper.cli.localizations.Messages;
import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.dependencieslist.DependenciesReader;
import org.pgcodekeeper.core.model.graph.DepcyResolver.DepcyGraphs;
import org.pgcodekeeper.core.utils.PhaseTimer;
import org.pgcodekeeper.core.utils.UnixPrintWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Batch diff runner: loads the comparison sides once and generates several
 * migration scripts from the same loaded models, one per manifest output.
 * <p>
 * Every output is byte-identical to a standalone DIFF run invoked with
 * {@code common + output args}, because each output rebuilds its settings by
 * parsing exactly those arguments and then reuses the loaded models and the
 * immutable project file filter compiled from the common section.
 * Load-affecting options (sources, {@code --ignore-schema}, charsets,
 * privileges, libraries) live in the shared common section; outputs may only
 * carry post-load script options, which {@link BatchManifest} enforces.
 */
public final class PgBatchDiffCli {

    private static final Logger LOG = LoggerFactory.getLogger(PgBatchDiffCli.class);

    private final CliArgs cliArgs;
    private List<Object> loadErrors = List.of();

    public PgBatchDiffCli(CliArgs cliArgs) {
        this.cliArgs = cliArgs;
    }

    public List<Object> getErrors() {
        return Collections.unmodifiableList(loadErrors);
    }

    /**
     * Runs the whole batch. Produces every output it can; a failed output
     * (safe-mode danger check or an unexpected error) does not stop the
     * remaining outputs. Prints one summary line per output on stderr.
     *
     * @return true if every output was generated successfully
     */
    public boolean run() throws CmdLineException, IOException, InterruptedException {
        BatchManifest manifest = BatchManifest.read(Paths.get(cliArgs.getBatchManifestPath()));

        CliArgs baseArgs = parseSection(manifest.commonArgs(), Messages.Batch_section_common);
        List<OutputTask> tasks = createOutputTasks(manifest, baseArgs);

        boolean previous = baseArgs.isCollectObjectReferences();
        baseArgs.setCollectObjectReferences(false);
        try {
            ComparisonLoaderFactories factories = createLoaderFactories(baseArgs);
            LoadedComparison loaded = loadOnce(baseArgs, factories);
            return produceOutputs(tasks, loaded, factories);
        } finally {
            baseArgs.setCollectObjectReferences(previous);
        }
    }

    List<OutputTask> createOutputTasks(BatchManifest manifest, CliArgs baseArgs)
            throws CmdLineException {
        List<OutputTask> tasks = new ArrayList<>();
        Set<Path> outputPaths = new HashSet<>();
        for (BatchManifest.Output output : manifest.outputs()) {
            List<String> effectiveArgs = Stream
                    .concat(manifest.commonArgs().stream(), output.args().stream())
                    .toList();
            CliArgs outputArgs = parseSection(effectiveArgs,
                    Messages.Batch_section_output.formatted(output.name()), baseArgs);
            if (outputArgs.getOutputTarget() == null) {
                throw badManifest(Messages.Batch_error_output_no_file.formatted(output.name()));
            }
            if (!outputPaths.add(Paths.get(outputArgs.getOutputTarget()).toAbsolutePath().normalize())) {
                throw badManifest(Messages.Batch_error_duplicate_output_path
                        .formatted(output.name(), outputArgs.getOutputTarget()));
            }
            tasks.add(new OutputTask(output.name(), outputArgs));
        }
        return tasks;
    }

    private LoadedComparison loadOnce(CliArgs baseArgs, ComparisonLoaderFactories factories)
            throws IOException, InterruptedException {
        applyAuxiliaryFiles(baseArgs);
        var loaded = PgCodeKeeperApi.loadForComparison(factories, baseArgs);

        loadErrors = new ArrayList<>(baseArgs.getErrors());
        if (!loadErrors.isEmpty() && !baseArgs.isIgnoreErrors()) {
            throw new LoadErrorsException(Messages.PgDiffCli_error_while_load_database);
        }
        return loaded;
    }

    private boolean produceOutputs(List<OutputTask> tasks, LoadedComparison loaded,
                                   ComparisonLoaderFactories factories) {
        List<String> summaries = new ArrayList<>();
        boolean allSucceeded = true;
        // The two dependency graphs are derived from the loaded models alone - no setting of
        // any output feeds into them - and the models are read-only from here on, so one pair
        // serves every output. Rebuilding them per output is the bulk of an output's cost on a
        // project-sized comparison. Built on first demand, so that a batch whose every output
        // comes out empty is charged for no graph at all, as it was before.
        Supplier<DepcyGraphs> sharedGraphs = PgCodeKeeperApi.sharedGraphs(
                loaded.oldDatabase(), loaded.newDatabase());
        for (OutputTask task : tasks) {
            long start = PhaseTimer.start();
            String failure = produceOutput(task, loaded, factories, sharedGraphs);
            PhaseTimer.end("batch_output", start, task.name()); //$NON-NLS-1$

            if (failure == null) {
                summaries.add(Messages.Batch_summary_ok
                        .formatted(task.name(), task.args().getOutputTarget()));
            } else {
                allSucceeded = false;
                summaries.add(Messages.Batch_summary_failed.formatted(task.name(), failure));
            }
        }

        for (String summary : summaries) {
            LOG.info(summary);
            System.err.println(summary);
        }
        return allSucceeded;
    }

    /**
     * Generates one output from the shared loaded models.
     *
     * @return null on success, otherwise a short failure reason
     */
    private String produceOutput(OutputTask task, LoadedComparison loaded,
                                 ComparisonLoaderFactories factories,
                                 Supplier<DepcyGraphs> sharedGraphs) {
        try {
            CliArgs outputArgs = task.args();
            outputArgs.setCollectObjectReferences(false);
            applyAuxiliaryFiles(outputArgs);
            factories.oldFactory().contributeCommonConfiguration(outputArgs);
            factories.newFactory().contributeCommonConfiguration(outputArgs);
            if (loaded.comparisonSettings().getVersion() != null) {
                outputArgs.setVersion(loaded.comparisonSettings().getVersion());
            }

            LOG.info(Messages.Main_log_create_script);
            String script = PgCodeKeeperApi.diff(outputArgs.getProvider(),
                    loaded.oldDatabase(), loaded.newDatabase(), outputArgs, sharedGraphs);

            if (outputArgs.isSafeMode()) {
                Set<DangerStatement> dangerTypes = PgCodeKeeperApi.checkDangerousStatements(
                        outputArgs.getProvider(), task.name(), script, outputArgs,
                        outputArgs.getAllowedDangers());
                if (!dangerTypes.isEmpty()) {
                    String dangerStatements = dangerTypes.stream().map(DangerStatement::name)
                            .collect(Collectors.joining(", ")); //$NON-NLS-1$
                    var logMsg = Messages.Main_log_contains_dangerous_statements
                            .formatted(dangerStatements);
                    LOG.warn(logMsg);
                    writeOutput(outputArgs,
                            "-- " + Messages.Main_danger_statements.formatted(dangerStatements)); //$NON-NLS-1$
                    return Messages.Batch_danger_reason.formatted(dangerStatements);
                }
            }

            writeOutput(outputArgs, script);
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return String.valueOf(ex.getLocalizedMessage());
        } catch (IOException | RuntimeException ex) {
            LOG.error(Messages.Main_log_running_error, ex);
            return String.valueOf(ex.getLocalizedMessage());
        }
    }

    /**
     * Re-applies the pre-load file arguments to a freshly parsed settings
     * instance in the same order a standalone run applies them: ignore lists,
     * the shared ignore-schema list, additional dependencies, then (for the
     * per-output instances) the project factory contributions.
     */
    private static void applyAuxiliaryFiles(CliArgs arguments) throws IOException {
        for (String ignorePath : arguments.getIgnoreLists()) {
            if (ignorePath != null) {
                arguments.addIgnoreList(Paths.get(ignorePath));
            }
        }
        if (arguments.getIgnoreSchemaListPath() != null) {
            arguments.addIgnoreSchemaList(Paths.get(arguments.getIgnoreSchemaListPath()));
        }
        if (arguments.getAdditionalDepsPath() != null) {
            arguments.addAdditionalDependencies(DependenciesReader
                    .getDependencies(Paths.get(arguments.getAdditionalDepsPath())));
        }
    }

    private static ComparisonLoaderFactories createLoaderFactories(CliArgs baseArgs) {
        var diffCli = new PgDiffCli(baseArgs);
        return new ComparisonLoaderFactories(
                diffCli.getDatabaseLoaderFactory(baseArgs.getOldSrc(),
                        baseArgs.getSourceLibXmls(), baseArgs.getSourceLibs(),
                        baseArgs.getSourceLibsWithoutPriv()),
                diffCli.getDatabaseLoaderFactory(baseArgs.getNewSrc(),
                        baseArgs.getTargetLibXmls(), baseArgs.getTargetLibs(),
                        baseArgs.getTargetLibsWithoutPriv()));
    }

    private static void writeOutput(CliArgs outputArgs, String text) throws IOException {
        try (PrintWriter writer = new UnixPrintWriter(
                outputArgs.getOutputTarget(), outputArgs.getOutCharsetName())) {
            writer.println(text);
        }
    }

    private static CliArgs parseSection(List<String> args, String section)
            throws CmdLineException {
        return parseSection(args, section, null);
    }

    private static CliArgs parseSection(List<String> args, String section,
            CliArgs preparedArgs) throws CmdLineException {
        CliArgs parsed = new CliArgs();
        if (preparedArgs != null) {
            parsed.prepareProjectFileFilterFrom(preparedArgs);
        }
        try {
            if (!parsed.parse(args.toArray(new String[0]))) {
                // --help and friends are rejected by manifest validation already
                throw badManifest(Messages.Batch_error_in_section
                        .formatted(section, Messages.Batch_error_option_forbidden
                                .formatted("--help"))); //$NON-NLS-1$
            }
        } catch (CmdLineException ex) {
            throw badManifest(Messages.Batch_error_in_section
                    .formatted(section, ex.getMessage()));
        }
        return parsed;
    }

    private static CmdLineException badManifest(String message) {
        return new CmdLineException(null, message, null);
    }

    record OutputTask(String name, CliArgs args) {
    }
}
