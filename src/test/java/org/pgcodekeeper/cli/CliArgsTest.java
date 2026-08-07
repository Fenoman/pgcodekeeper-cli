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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.kohsuke.args4j.CmdLineException;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.settings.ProjectFileFilter;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;

class CliArgsTest {

        static {
                // The diagnostics asserted below are the English CLI contract.
                Locale.setDefault(Locale.ENGLISH);
        }

        @Test
        void projectFileFilterOptionIsAcceptedInProjectLoadingModes(@TempDir Path tempDir)
                        throws Exception {
                Path filter = Files.writeString(tempDir.resolve("project.filter"),
                                "EXCLUDE PATH SCHEMA/public/TABLE/hidden.sql\n");
                List<String[]> commands = List.of(
                                new String[] { "--project-file-filter", filter.toString(),
                                                "source", "target" },
                                new String[] { "--mode", "PARSE", "--project-file-filter",
                                                filter.toString(), "-o", "output", "source" },
                                new String[] { "--mode", "GRAPH", "--project-file-filter",
                                                filter.toString(), "source" });

                for (String[] command : commands) {
                        var args = new CliArgs();
                        Assertions.assertTrue(args.parse(command));
                        Assertions.assertEquals(filter.toString(),
                                        args.getProjectFileFilterPath());
                        Assertions.assertNotSame(ProjectFileFilter.ALLOW_ALL,
                                        args.getProjectFileFilter());
                        Assertions.assertFalse(args.getProjectFileFilter().isAllowed(
                                        "SCHEMA/public/TABLE/hidden.sql"));

                        var copy = args.shallowCopy();
                        Assertions.assertEquals(filter.toString(),
                                        copy.getProjectFileFilterPath());
                        Assertions.assertSame(args.getProjectFileFilter(),
                                        copy.getProjectFileFilter());
                }
        }

        @Test
        void preparedProjectFileFilterIsIgnoredWhenRawPathDiffers(@TempDir Path tempDir)
                        throws Exception {
                Path preparedPath = Files.writeString(tempDir.resolve("prepared.filter"),
                                "EXCLUDE PATH SCHEMA/public/TABLE/prepared.sql\n");
                Path actualPath = Files.writeString(tempDir.resolve("actual.filter"),
                                "EXCLUDE PATH SCHEMA/public/TABLE/actual.sql\n");
                var preparedArgs = new CliArgs();
                Assertions.assertTrue(preparedArgs.parse(new String[] {
                                "--project-file-filter", preparedPath.toString(),
                                "source", "target"
                }));

                var actualArgs = new CliArgs();
                actualArgs.prepareProjectFileFilterFrom(preparedArgs);
                Assertions.assertTrue(actualArgs.parse(new String[] {
                                "--project-file-filter", actualPath.toString(),
                                "source", "target"
                }));

                Assertions.assertAll(
                                () -> Assertions.assertEquals(actualPath.toString(),
                                                actualArgs.getProjectFileFilterPath()),
                                () -> Assertions.assertNotSame(preparedArgs.getProjectFileFilter(),
                                                actualArgs.getProjectFileFilter()),
                                () -> Assertions.assertTrue(actualArgs.getProjectFileFilter()
                                                .isAllowed("SCHEMA/public/TABLE/prepared.sql")),
                                () -> Assertions.assertFalse(actualArgs.getProjectFileFilter()
                                                .isAllowed("SCHEMA/public/TABLE/actual.sql")));
        }

        @Test
        void unresolvedProjectFileFilterCannotBypassNormalFileRead(@TempDir Path tempDir) {
                Path missing = tempDir.resolve("missing.filter");
                var unresolvedArgs = new CliArgs();
                Assertions.assertThrows(CmdLineException.class,
                                () -> unresolvedArgs.parse(new String[] {
                                                "--mode", "BATCH", "--batch-manifest", "batch.json",
                                                "--project-file-filter", missing.toString()
                                }));

                var actualArgs = new CliArgs();
                actualArgs.prepareProjectFileFilterFrom(unresolvedArgs);
                var exception = Assertions.assertThrows(CmdLineException.class,
                                () -> actualArgs.parse(new String[] {
                                                "--project-file-filter", missing.toString(),
                                                "source", "target"
                                }));

                Assertions.assertTrue(exception.getMessage().startsWith(
                                "Failed to read project file filter \"" + missing + "\":"),
                                exception::getMessage);
        }

        @Test
        void projectFileFilterOptionIsRejectedByOuterBatchBeforeFileRead(@TempDir Path tempDir) {
                Path missing = tempDir.resolve("missing.filter");
                var args = new CliArgs();

                var exception = Assertions.assertThrows(CmdLineException.class,
                                () -> args.parse(new String[] {
                                                "--mode", "BATCH", "--batch-manifest", "batch.json",
                                                "--project-file-filter", missing.toString()
                                }));

                Assertions.assertEquals(
                                "--project-file-filter cannot be used with --mode BATCH option",
                                exception.getMessage());
        }

        @Test
        void missingProjectFileFilterIsLocalizedCmdLineFailure(@TempDir Path tempDir) {
                Path missing = tempDir.resolve("missing.filter");

                assertProjectFileFilterFailure(missing.toString(), missing.toString());
        }

        @Test
        void invalidProjectFileFilterIsCompiledBeforeProviderConstruction(@TempDir Path tempDir)
                        throws IOException {
                Path invalid = Files.writeString(tempDir.resolve("invalid.filter"),
                                "EXCLUDE PATH\n");
                var args = new CliArgs();

                var exception = Assertions.assertThrows(CmdLineException.class,
                                () -> args.parse(new String[] {
                                                "--db-type", "BROKEN", "--project-file-filter",
                                                invalid.toString(), "source", "target"
                                }));

                Assertions.assertAll(
                                () -> Assertions.assertTrue(exception.getMessage().startsWith(
                                                "Failed to read project file filter \""
                                                                + invalid + "\":"),
                                                exception::getMessage),
                                () -> Assertions.assertTrue(exception.getMessage().contains(
                                                "expected ACTION KIND VALUE"),
                                                exception::getMessage));
        }

        @Test
        void nonUtf8ProjectFileFilterIsLocalizedCmdLineFailure(@TempDir Path tempDir)
                        throws IOException {
                Path invalid = tempDir.resolve("non-utf8.filter");
                Files.write(invalid, new byte[] { (byte) 0xC3, (byte) 0x28 });

                assertProjectFileFilterFailure(invalid.toString(), "invalid UTF-8");
        }

        @Test
        void invalidProjectFileFilterPathIsLocalizedCmdLineFailure() {
                String invalid = "bad\0project.filter";

                assertProjectFileFilterFailure(invalid, "Nul character not allowed");
        }

        @Test
        void projectFileFilterMessagesAreLocalizedInEnglishAndRussian() {
                var english = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages", Locale.ENGLISH);
                var russian = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages",
                                Locale.forLanguageTag("ru-RU"));

                Assertions.assertAll(
                                () -> Assertions.assertEquals(
                                                "filter top-level project files using ordered rules "
                                                                + "from a UTF-8 file (opt-in)",
                                                english.getString("CliArgs_project_file_filter")),
                                () -> Assertions.assertEquals(
                                                "Failed to read project file filter \"%s\": %s",
                                                english.getString(
                                                                "CliArgs_error_project_file_filter")),
                                () -> Assertions.assertEquals(
                                                "фильтровать файлы верхнего уровня проекта "
                                                                + "упорядоченными правилами из файла UTF-8 "
                                                                + "(только при явном указании)",
                                                russian.getString("CliArgs_project_file_filter")),
                                () -> Assertions.assertEquals(
                                                "Не удалось прочитать фильтр файлов проекта \"%s\": %s",
                                                russian.getString(
                                                                "CliArgs_error_project_file_filter")));
        }

        private static void assertProjectFileFilterFailure(String path,
                        String expectedDetail) {
                var args = new CliArgs();
                var exception = Assertions.assertThrows(CmdLineException.class,
                                () -> args.parse(new String[] {
                                                "--project-file-filter", path, "source", "target"
                                }));

                Assertions.assertAll(
                                () -> Assertions.assertTrue(exception.getMessage().startsWith(
                                                "Failed to read project file filter \"" + path + "\":"),
                                                exception::getMessage),
                                () -> Assertions.assertTrue(exception.getMessage()
                                                .contains(expectedDetail), exception::getMessage));
        }

        // CLI and Core deliberately expose different consumer-specific defaults.
        @Test
        void jdbcFetchSizeDefaultsTo512AndKeepsZeroExpressible() throws CmdLineException {
                var args = new CliArgs();

                Assertions.assertEquals(512, args.getJdbcFetchSize());
                Assertions.assertTrue(args.parse(new String[] { "source", "target" }));
                Assertions.assertEquals(512, args.getJdbcFetchSize());
                Assertions.assertEquals(512, args.shallowCopy().getJdbcFetchSize());

                // an explicit 0 remains expressible: it keeps the JDBC driver
                // default (pgjdbc fetches everything at once)
                var zeroArgs = new CliArgs();
                Assertions.assertTrue(zeroArgs.parse(new String[] {
                                "--jdbc-fetch-size", "0", "source", "target"
                }));
                Assertions.assertEquals(0, zeroArgs.getJdbcFetchSize());
                Assertions.assertEquals(0, zeroArgs.shallowCopy().getJdbcFetchSize());

                var explicitArgs = new CliArgs();
                Assertions.assertTrue(explicitArgs.parse(new String[] {
                                "--jdbc-fetch-size", "17", "source", "target"
                }));
                Assertions.assertEquals(17, explicitArgs.getJdbcFetchSize());
                Assertions.assertEquals(17, explicitArgs.shallowCopy().getJdbcFetchSize());
        }

        @Test
        void perfDefaultsMatrixResolvesWithoutAnyPerfFlags() throws CmdLineException {
                var args = new CliArgs();

                Assertions.assertTrue(args.parse(new String[] { "source", "target" }));

                Assertions.assertTrue(args.isParallelLoad());
                Assertions.assertTrue(args.isPgRoutineBodyHashFirst());
                Assertions.assertTrue(args.requiresComparisonLoaderFactories());
                Assertions.assertTrue(args.isPgRoutineBodySkipMatchedAnalysis());
                Assertions.assertEquals(512, args.getJdbcFetchSize());
                Assertions.assertEquals(3, args.getPgParallelCatalogReaders());
                Assertions.assertEquals(256, args.getPgRoutineBodyResidualBatchCount());
                Assertions.assertEquals(33_554_432L, args.getPgRoutineBodyResidualBatchBytes());

                var copy = args.shallowCopy();
                Assertions.assertTrue(copy.isParallelLoad());
                Assertions.assertTrue(copy.isPgRoutineBodyHashFirst());
                Assertions.assertEquals(512, copy.getJdbcFetchSize());
                Assertions.assertEquals(3, copy.getPgParallelCatalogReaders());
                Assertions.assertEquals(256, copy.getPgRoutineBodyResidualBatchCount());
                Assertions.assertEquals(33_554_432L, copy.getPgRoutineBodyResidualBatchBytes());
        }

        @Test
        void explicitPerfDefaultsResolveLikeBareInvocation() throws CmdLineException {
                // Explicit default values resolve exactly like a bare invocation.
                var args = new CliArgs();

                Assertions.assertTrue(args.parse(new String[] {
                                "--parallel-load",
                                "--jdbc-fetch-size", "512",
                                "--pg-routine-body-hash-first",
                                "--pg-routine-body-residual-batch-count", "256",
                                "--pg-routine-body-residual-batch-bytes", "33554432",
                                "--pg-parallel-catalog-readers", "3",
                                "source", "target"
                }));

                Assertions.assertTrue(args.isParallelLoad());
                Assertions.assertTrue(args.isPgRoutineBodyHashFirst());
                Assertions.assertEquals(512, args.getJdbcFetchSize());
                Assertions.assertEquals(3, args.getPgParallelCatalogReaders());
                Assertions.assertEquals(256, args.getPgRoutineBodyResidualBatchCount());
                Assertions.assertEquals(33_554_432L, args.getPgRoutineBodyResidualBatchBytes());
        }

        @Test
        void noParallelLoadSelectsSequentialLoadingAndFullBodyExchange()
                        throws CmdLineException {
                var args = new CliArgs();

                Assertions.assertTrue(args.parse(new String[] {
                                "--no-parallel-load", "source", "target"
                }));

                Assertions.assertFalse(args.isParallelLoad());
                // Hash-first runs inside the paired parallel comparison load.
                Assertions.assertFalse(args.isPgRoutineBodyHashFirst());
                Assertions.assertFalse(args.requiresComparisonLoaderFactories());
                Assertions.assertFalse(args.shallowCopy().isParallelLoad());
                Assertions.assertFalse(args.copy().isPgRoutineBodyHashFirst());
        }

        @Test
        void pgRoutineBodyNoHashFirstKeepsParallelLoadAndDisablesBodyExchange()
                        throws CmdLineException {
                var args = new CliArgs();

                Assertions.assertTrue(args.parse(new String[] {
                                "--pg-routine-body-no-hash-first", "source", "target"
                }));

                Assertions.assertTrue(args.isParallelLoad());
                Assertions.assertFalse(args.isPgRoutineBodyHashFirst());
                Assertions.assertFalse(args.requiresComparisonLoaderFactories());
                Assertions.assertFalse(args.copy().isPgRoutineBodyHashFirst());
                Assertions.assertFalse(args.shallowCopy().isPgRoutineBodyHashFirst());
        }

        @Test
        void hashFirstDefaultStaysOffOutsidePgDiffComparisons() throws CmdLineException {
                // Hash-first applies only to PostgreSQL DIFF comparisons.
                var parseArgs = new CliArgs();
                Assertions.assertTrue(parseArgs.parse(new String[] {
                                "--mode", "PARSE", "-o", "output", "source" }));
                Assertions.assertFalse(parseArgs.isPgRoutineBodyHashFirst());
                Assertions.assertFalse(parseArgs.requiresComparisonLoaderFactories());
                Assertions.assertTrue(parseArgs.isParallelLoad());

                var graphArgs = new CliArgs();
                Assertions.assertTrue(graphArgs.parse(new String[] {
                                "--mode", "GRAPH", "source" }));
                Assertions.assertFalse(graphArgs.isPgRoutineBodyHashFirst());

                var batchArgs = new CliArgs();
                Assertions.assertTrue(batchArgs.parse(new String[] {
                                "--mode", "BATCH", "--batch-manifest", "batch.json" }));
                Assertions.assertFalse(batchArgs.isPgRoutineBodyHashFirst());

                var msArgs = new CliArgs();
                Assertions.assertTrue(msArgs.parse(new String[] {
                                "--db-type", "MS", "source", "target" }));
                Assertions.assertFalse(msArgs.isPgRoutineBodyHashFirst());
                Assertions.assertTrue(msArgs.isParallelLoad());

                var chArgs = new CliArgs();
                Assertions.assertTrue(chArgs.parse(new String[] {
                                "--db-type", "CH", "source", "target" }));
                Assertions.assertFalse(chArgs.isPgRoutineBodyHashFirst());
        }

        @Test
        void hashFirstDependentOptionsUseImplicitDefault() throws CmdLineException {
                // The implicit hash-first value enables its dependent options.
                var args = new CliArgs();

                Assertions.assertTrue(args.parse(new String[] {
                                "--pg-routine-body-residual-batch-count", "17",
                                "--pg-routine-body-residual-batch-bytes", "4096",
                                "--pg-catalog-cache-dir", "/tmp/pgck-cache",
                                "source", "target"
                }));

                Assertions.assertTrue(args.isPgRoutineBodyHashFirst());
                Assertions.assertEquals(17, args.getPgRoutineBodyResidualBatchCount());
                Assertions.assertEquals(4096L, args.getPgRoutineBodyResidualBatchBytes());
                Assertions.assertEquals("/tmp/pgck-cache", args.getPgCatalogCacheDir());
        }

        @Test
        void pgRoutineBodyHashFirstDefaultsAndCopiesParsedValues() throws CmdLineException {
                var args = new CliArgs();

                Assertions.assertFalse(args.isPgRoutineBodyHashFirst());
                Assertions.assertEquals(ISettings.DEFAULT_PG_ROUTINE_BODY_RESIDUAL_BATCH_COUNT,
                                args.getPgRoutineBodyResidualBatchCount());
                Assertions.assertEquals(ISettings.DEFAULT_PG_ROUTINE_BODY_RESIDUAL_BATCH_BYTES,
                                args.getPgRoutineBodyResidualBatchBytes());
                Assertions.assertFalse(args.requiresComparisonLoaderFactories());

                Assertions.assertTrue(args.parse(new String[] {
                                "--parallel-load",
                                "--pg-routine-body-hash-first",
                                "--pg-routine-body-residual-batch-count", "17",
                                "--pg-routine-body-residual-batch-bytes", "4096",
                                "source", "target"
                }));

                Assertions.assertTrue(args.isPgRoutineBodyHashFirst());
                Assertions.assertEquals(17, args.getPgRoutineBodyResidualBatchCount());
                Assertions.assertEquals(4096L, args.getPgRoutineBodyResidualBatchBytes());
                Assertions.assertTrue(args.requiresComparisonLoaderFactories());

                var copy = args.copy();
                Assertions.assertTrue(copy.isPgRoutineBodyHashFirst());
                Assertions.assertEquals(17, copy.getPgRoutineBodyResidualBatchCount());
                Assertions.assertEquals(4096L, copy.getPgRoutineBodyResidualBatchBytes());
                Assertions.assertTrue(copy.requiresComparisonLoaderFactories());
        }

        @Test
        void pgRoutineBodyHashFirstProgrammaticSettersUseTheSameStateAsCopy() {
                var args = new CliArgs();

                args.setPgRoutineBodyHashFirst(true);
                args.setPgRoutineBodyResidualBatchCount(31);
                args.setPgRoutineBodyResidualBatchBytes(8192L);

                Assertions.assertTrue(args.isPgRoutineBodyHashFirst());
                Assertions.assertEquals(31, args.getPgRoutineBodyResidualBatchCount());
                Assertions.assertEquals(8192L, args.getPgRoutineBodyResidualBatchBytes());
                Assertions.assertTrue(args.requiresComparisonLoaderFactories());

                var copy = args.copy();
                Assertions.assertTrue(copy.isPgRoutineBodyHashFirst());
                Assertions.assertEquals(31, copy.getPgRoutineBodyResidualBatchCount());
                Assertions.assertEquals(8192L, copy.getPgRoutineBodyResidualBatchBytes());
                Assertions.assertTrue(copy.requiresComparisonLoaderFactories());
        }

        @Test
        void pgRoutineBodySkipMatchedAnalysisIsOnByDefaultAndSurvivesParseAndCopies()
                        throws CmdLineException {
                var args = new CliArgs();

                Assertions.assertTrue(args.isPgRoutineBodySkipMatchedAnalysis());

                Assertions.assertTrue(args.parse(new String[] { "source", "target" }));

                Assertions.assertTrue(args.isPgRoutineBodySkipMatchedAnalysis());
                Assertions.assertTrue(args.copy().isPgRoutineBodySkipMatchedAnalysis());
                Assertions.assertTrue(args.shallowCopy().isPgRoutineBodySkipMatchedAnalysis());
        }

        @Test
        void pgRoutineBodyNoSkipMatchedAnalysisSwitchDisablesStandalone()
                        throws CmdLineException {
                var args = new CliArgs();

                Assertions.assertTrue(args.parse(new String[] {
                                "--pg-routine-body-no-skip-matched-analysis",
                                "source", "target"
                }));

                Assertions.assertFalse(args.isPgRoutineBodySkipMatchedAnalysis());
                Assertions.assertFalse(args.copy().isPgRoutineBodySkipMatchedAnalysis());
                Assertions.assertFalse(args.shallowCopy().isPgRoutineBodySkipMatchedAnalysis());
        }

        @Test
        void pgRoutineBodySkipMatchedAnalysisProgrammaticSetterUsesTheSameStateAsCopy() {
                var args = new CliArgs();

                args.setPgRoutineBodySkipMatchedAnalysis(false);

                Assertions.assertFalse(args.isPgRoutineBodySkipMatchedAnalysis());
                Assertions.assertFalse(args.copy().isPgRoutineBodySkipMatchedAnalysis());
        }

        @Test
        void pgCatalogCacheDefaultsAndCopiesParsedValues() throws CmdLineException {
                var args = new CliArgs();

                Assertions.assertNull(args.getPgCatalogCacheDir());
                Assertions.assertEquals(ISettings.DEFAULT_PG_CATALOG_CACHE_MAX_MB,
                                args.getPgCatalogCacheMaxMb());
                Assertions.assertFalse(args.isPgCatalogCacheRows());

                Assertions.assertTrue(args.parse(new String[] {
                                "--parallel-load",
                                "--pg-routine-body-hash-first",
                                "--pg-catalog-cache-dir", "/tmp/pgck-cache",
                                "--pg-catalog-cache-max-mb", "64",
                                "--pg-catalog-cache-rows",
                                "source", "target"
                }));

                Assertions.assertEquals("/tmp/pgck-cache", args.getPgCatalogCacheDir());
                Assertions.assertEquals(64L, args.getPgCatalogCacheMaxMb());
                Assertions.assertTrue(args.isPgCatalogCacheRows());

                var copy = args.copy();
                Assertions.assertEquals("/tmp/pgck-cache", copy.getPgCatalogCacheDir());
                Assertions.assertEquals(64L, copy.getPgCatalogCacheMaxMb());
                Assertions.assertTrue(copy.isPgCatalogCacheRows());
        }

        @Test
        void pgCatalogCacheRowsStaysOptionalWithCacheDirAlone() throws CmdLineException {
                var args = new CliArgs();

                Assertions.assertTrue(args.parse(new String[] {
                                "--parallel-load",
                                "--pg-routine-body-hash-first",
                                "--pg-catalog-cache-dir", "/tmp/pgck-cache",
                                "source", "target"
                }));

                Assertions.assertEquals("/tmp/pgck-cache", args.getPgCatalogCacheDir());
                Assertions.assertFalse(args.isPgCatalogCacheRows());
                Assertions.assertFalse(args.copy().isPgCatalogCacheRows());
        }

        // CLI and Core deliberately expose different consumer-specific defaults.
        @Test
        void pgParallelCatalogReadersDefaultsTo3AndKeepsZeroExpressible()
                        throws CmdLineException {
                var args = new CliArgs();

                Assertions.assertEquals(3, args.getPgParallelCatalogReaders());
                Assertions.assertTrue(args.parse(new String[] {
                                "--pg-parallel-catalog-readers", "5", "source", "target"
                }));
                Assertions.assertEquals(5, args.getPgParallelCatalogReaders());
                Assertions.assertEquals(5, args.shallowCopy().getPgParallelCatalogReaders());
                Assertions.assertEquals(5, args.copy().getPgParallelCatalogReaders());

                // an explicit 0 remains expressible and disables the lanes
                var zeroArgs = new CliArgs();
                Assertions.assertTrue(zeroArgs.parse(new String[] {
                                "--pg-parallel-catalog-readers", "0", "source", "target"
                }));
                Assertions.assertEquals(0, zeroArgs.getPgParallelCatalogReaders());
                Assertions.assertEquals(0, zeroArgs.shallowCopy().getPgParallelCatalogReaders());
        }

        @Test
        void pgParallelCatalogReadersAreRejectedOutsidePostgreSql() {
                // The option is PostgreSQL-only, like every other --pg-* switch.
                var msArgs = new CliArgs();
                var msException = Assertions.assertThrows(CmdLineException.class,
                                () -> msArgs.parse(new String[] {
                                                "--db-type", "MS",
                                                "--pg-parallel-catalog-readers", "4",
                                                "source", "target"
                                }));
                Assertions.assertEquals(
                                "--pg-parallel-catalog-readers cannot be used with --db-type MS option",
                                msException.getMessage());

                var chArgs = new CliArgs();
                var chException = Assertions.assertThrows(CmdLineException.class,
                                () -> chArgs.parse(new String[] {
                                                "--db-type", "CH",
                                                "--pg-parallel-catalog-readers", "4",
                                                "source", "target"
                                }));
                Assertions.assertEquals(
                                "--pg-parallel-catalog-readers cannot be used with --db-type CH option",
                                chException.getMessage());
        }

        @Test
        void pgParallelCatalogReadersDefaultStaysOffOutsidePostgreSql()
                        throws CmdLineException {
                // Only the PostgreSQL JDBC loader reads the setting, so the CLI
                // profile must not follow other dialects - the same rule that
                // keeps hash-first off there.
                var msArgs = new CliArgs();
                Assertions.assertTrue(msArgs.parse(new String[] {
                                "--db-type", "MS", "source", "target"
                }));
                Assertions.assertEquals(0, msArgs.getPgParallelCatalogReaders());
                Assertions.assertEquals(0, msArgs.shallowCopy().getPgParallelCatalogReaders());
                Assertions.assertEquals(0, msArgs.copy().getPgParallelCatalogReaders());

                var chArgs = new CliArgs();
                Assertions.assertTrue(chArgs.parse(new String[] {
                                "--db-type", "CH", "source", "target"
                }));
                Assertions.assertEquals(0, chArgs.getPgParallelCatalogReaders());

                // the lanes still speed up a live PostgreSQL load outside DIFF
                var graphArgs = new CliArgs();
                Assertions.assertTrue(graphArgs.parse(new String[] {
                                "--mode", "GRAPH", "jdbc:postgresql:q"
                }));
                Assertions.assertEquals(3, graphArgs.getPgParallelCatalogReaders());
        }

        @Test
        void negativePgParallelCatalogReadersIsRejected() {
                var args = new CliArgs();

                var exception = Assertions.assertThrows(CmdLineException.class,
                                () -> args.parse(new String[] {
                                                "--pg-parallel-catalog-readers", "-1", "source", "target"
                                }));

                Assertions.assertEquals(
                                "PostgreSQL parallel catalog reader count must not be negative.",
                                exception.getMessage());
        }

        @Test
        void pgParallelCatalogReadersUsageIsLocalizedInEnglishAndRussian() {
                var english = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages", Locale.ENGLISH);
                var russian = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages", Locale.forLanguageTag("ru-RU"));

                Assertions.assertEquals(
                                "read PostgreSQL catalogs with N extra connections sharing the primary snapshot; "
                                                + "0 keeps the sequential flow; loads that cannot share the snapshot "
                                                + "fall back to sequential reading (CLI default: 3; the core/IDE default stays 0)",
                                english.getString("CliArgs_pg_parallel_catalog_readers"));
                Assertions.assertEquals(
                                "читать каталоги PostgreSQL через N дополнительных соединений, разделяющих "
                                                + "снимок основного соединения; 0 сохраняет последовательное "
                                                + "чтение; загрузки без общего снимка автоматически возвращаются к "
                                                + "последовательному чтению (по умолчанию в CLI: 3; в ядре/IDE остаётся 0)",
                                russian.getString("CliArgs_pg_parallel_catalog_readers"));
                Assertions.assertEquals(
                                "PostgreSQL parallel catalog reader count must not be negative.",
                                english.getString("CliArgs_error_pg_parallel_catalog_readers_negative"));
                Assertions.assertEquals(
                                "Число параллельных читателей каталога PostgreSQL не должно быть отрицательным.",
                                russian.getString("CliArgs_error_pg_parallel_catalog_readers_negative"));
        }

        @Test
        void pgCatalogCacheProgrammaticSettersUseTheSameStateAsCopy() {
                var args = new CliArgs();

                args.setPgCatalogCacheDir("/tmp/pgck-cache");
                args.setPgCatalogCacheMaxMb(128L);

                Assertions.assertEquals("/tmp/pgck-cache", args.getPgCatalogCacheDir());
                Assertions.assertEquals(128L, args.getPgCatalogCacheMaxMb());

                var copy = args.copy();
                Assertions.assertEquals("/tmp/pgck-cache", copy.getPgCatalogCacheDir());
                Assertions.assertEquals(128L, copy.getPgCatalogCacheMaxMb());
        }

        @Test
        void jdbcFetchSizeUsageIsLocalizedInEnglishAndRussian() {
                var english = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages", Locale.ENGLISH);
                var russian = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages", Locale.forLanguageTag("ru-RU"));

                Assertions.assertEquals(
                                "JDBC catalog fetch size; 0 keeps the driver default (default: 512)",
                                english.getString("CliArgs_jdbc_fetch_size"));
                Assertions.assertEquals(
                                "размер выборки каталога JDBC; 0 сохраняет значение драйвера по умолчанию "
                                                + "(по умолчанию: 512)",
                                russian.getString("CliArgs_jdbc_fetch_size"));
                Assertions.assertEquals("JDBC fetch size must not be negative.",
                                english.getString("CliArgs_error_jdbc_fetch_size_negative"));
                Assertions.assertEquals("Размер выборки JDBC не может быть отрицательным.",
                                russian.getString("CliArgs_error_jdbc_fetch_size_negative"));
        }

        @Test
        void pgRoutineBodyHashFirstUsageIsLocalizedInEnglishAndRussian() {
                var english = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages", Locale.ENGLISH);
                var russian = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages", Locale.forLanguageTag("ru-RU"));

                Assertions.assertEquals(
                                "reuse matching PostgreSQL routine bodies from the project and fetch only residual bodies "
                                                + "(default for PostgreSQL DIFF mode; this flag is a no-op kept for script "
                                                + "compatibility, see --pg-routine-body-no-hash-first)",
                                english.getString("CliArgs_pg_routine_body_hash_first"));
                Assertions.assertEquals(
                                "always fetch full PostgreSQL routine bodies from the database (rollback switch for "
                                                + "the hash-first default); cannot be combined with the hash-first-only options "
                                                + "--pg-routine-body-residual-batch-count, --pg-routine-body-residual-batch-bytes "
                                                + "and --pg-catalog-cache-dir",
                                english.getString("CliArgs_pg_routine_body_no_hash_first"));
                Assertions.assertEquals(
                                "maximum PostgreSQL residual routine bodies per JDBC batch (default: 256)",
                                english.getString("CliArgs_pg_routine_body_residual_batch_count"));
                Assertions.assertEquals(
                                "predicted UTF-8 grouping budget for each PostgreSQL residual routine body batch; "
                                                + "one oversized body is fetched alone (default: 33554432)",
                                english.getString("CliArgs_pg_routine_body_residual_batch_bytes"));
                Assertions.assertEquals(
                                "PostgreSQL residual routine body batch count must be positive.",
                                english.getString("CliArgs_error_pg_routine_body_residual_batch_count_non_positive"));
                Assertions.assertEquals(
                                "PostgreSQL residual routine body batch byte budget must be positive.",
                                english.getString("CliArgs_error_pg_routine_body_residual_batch_bytes_non_positive"));

                Assertions.assertEquals(
                                "переиспользовать совпадающие тела подпрограмм PostgreSQL из проекта и загружать только "
                                                + "оставшиеся тела (по умолчанию для режима DIFF PostgreSQL; флаг оставлен для "
                                                + "совместимости скриптов и ничего не меняет, см. --pg-routine-body-no-hash-first)",
                                russian.getString("CliArgs_pg_routine_body_hash_first"));
                Assertions.assertEquals(
                                "всегда загружать полные тела подпрограмм PostgreSQL из базы данных (переключатель отката "
                                                + "для режима hash-first по умолчанию); несовместим с опциями "
                                                + "--pg-routine-body-residual-batch-count, --pg-routine-body-residual-batch-bytes "
                                                + "и --pg-catalog-cache-dir, действующими только на пути hash-first",
                                russian.getString("CliArgs_pg_routine_body_no_hash_first"));
                Assertions.assertEquals(
                                "максимальное количество оставшихся тел подпрограмм PostgreSQL в одном пакете JDBC "
                                                + "(по умолчанию: 256)",
                                russian.getString("CliArgs_pg_routine_body_residual_batch_count"));
                Assertions.assertEquals(
                                "прогнозируемый бюджет UTF-8 для группировки оставшихся тел подпрограмм PostgreSQL в один "
                                                + "пакет; одно тело, превышающее бюджет, загружается отдельно "
                                                + "(по умолчанию: 33554432)",
                                russian.getString("CliArgs_pg_routine_body_residual_batch_bytes"));
                Assertions.assertEquals(
                                "Количество тел подпрограмм PostgreSQL в остаточном пакете должно быть положительным.",
                                russian.getString("CliArgs_error_pg_routine_body_residual_batch_count_non_positive"));
                Assertions.assertEquals(
                                "Бюджет байтов остаточного пакета тел подпрограмм PostgreSQL должен быть положительным.",
                                russian.getString("CliArgs_error_pg_routine_body_residual_batch_bytes_non_positive"));
        }

        @Test
        void pgCatalogCacheUsageIsLocalizedInEnglishAndRussian() {
                var english = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages", Locale.ENGLISH);
                var russian = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages", Locale.forLanguageTag("ru-RU"));

                Assertions.assertEquals(
                                "persistent local cache directory for PostgreSQL routine bodies fetched "
                                                + "on the hash-first path; repeat comparisons skip downloading unchanged bodies",
                                english.getString("CliArgs_pg_catalog_cache_dir"));
                Assertions.assertEquals(
                                "size cap of the PostgreSQL catalog cache in megabytes; "
                                                + "oldest entries are pruned after a run",
                                english.getString("CliArgs_pg_catalog_cache_max_mb"));
                Assertions.assertEquals(
                                "PostgreSQL catalog cache size limit must be positive.",
                                english.getString("CliArgs_error_pg_catalog_cache_max_mb_non_positive"));

                Assertions.assertEquals(
                                "каталог постоянного локального кэша тел подпрограмм PostgreSQL, "
                                                + "загружаемых на остаточном пути; повторные сравнения пропускают "
                                                + "загрузку неизменённых тел",
                                russian.getString("CliArgs_pg_catalog_cache_dir"));
                Assertions.assertEquals(
                                "максимальный размер кэша каталога PostgreSQL в мегабайтах; "
                                                + "самые старые записи удаляются после запуска",
                                russian.getString("CliArgs_pg_catalog_cache_max_mb"));
                Assertions.assertEquals(
                                "Максимальный размер кэша каталога PostgreSQL должен быть положительным.",
                                russian.getString("CliArgs_error_pg_catalog_cache_max_mb_non_positive"));
        }

        @Test
        void parallelLoadUsageIsLocalizedInEnglishAndRussian() {
                var english = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages", Locale.ENGLISH);
                var russian = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages", Locale.forLanguageTag("ru-RU"));

                Assertions.assertEquals(
                                "use parallel database loading (default; this flag is a no-op kept for script "
                                                + "compatibility, see --no-parallel-load)",
                                english.getString("CliArgs_use_parallel_load"));
                Assertions.assertEquals(
                                "load the comparison sides sequentially (rollback switch for the --parallel-load "
                                                + "default); also turns the hash-first routine body default off",
                                english.getString("CliArgs_no_parallel_load"));
                Assertions.assertEquals(
                                "option \"%s\" cannot be used with the option(s) [%s]",
                                english.getString("CliArgs_error_conflicting_options"));

                Assertions.assertEquals(
                                "использовать параллельную загрузку БД (по умолчанию; флаг оставлен для "
                                                + "совместимости скриптов и ничего не меняет, см. --no-parallel-load)",
                                russian.getString("CliArgs_use_parallel_load"));
                Assertions.assertEquals(
                                "загружать стороны сравнения последовательно (переключатель отката для "
                                                + "--parallel-load по умолчанию); также отключает режим hash-first по умолчанию",
                                russian.getString("CliArgs_no_parallel_load"));
                Assertions.assertEquals(
                                "опция \"%s\" не может использоваться вместе с опцией [%s]",
                                russian.getString("CliArgs_error_conflicting_options"));
        }

        @Test
        void negativeJdbcFetchSizeIsRejectedBeforeClearCacheEarlyExit() {
                var args = new CliArgs();

                var exception = Assertions.assertThrows(CmdLineException.class,
                                () -> args.parse(new String[] {
                                                "--clear-lib-cache", "--jdbc-fetch-size", "-1"
                                }));

                Assertions.assertEquals("JDBC fetch size must not be negative.", exception.getMessage());
        }

        @Test
        void nonPositivePgRoutineBodyLimitsAreRejectedBeforeClearCacheEarlyExit() {
                var countArgs = new CliArgs();
                var countException = Assertions.assertThrows(CmdLineException.class,
                                () -> countArgs.parse(new String[] {
                                                "--clear-lib-cache",
                                                "--parallel-load",
                                                "--pg-routine-body-hash-first",
                                                "--pg-routine-body-residual-batch-count", "0"
                                }));
                Assertions.assertEquals(
                                "PostgreSQL residual routine body batch count must be positive.",
                                countException.getMessage());

                var bytesArgs = new CliArgs();
                var bytesException = Assertions.assertThrows(CmdLineException.class,
                                () -> bytesArgs.parse(new String[] {
                                                "--clear-lib-cache",
                                                "--parallel-load",
                                                "--pg-routine-body-hash-first",
                                                "--pg-routine-body-residual-batch-bytes", "0"
                                }));
                Assertions.assertEquals(
                                "PostgreSQL residual routine body batch byte budget must be positive.",
                                bytesException.getMessage());
        }

        // args4j splits an option name at the parser's value delimiter, whose
        // library default is a space. A 0-argument option then never advances
        // the token cursor, so the parse loop spins forever at full CPU. The
        // separate thread makes a regression fail on the timeout instead of
        // hanging the build.
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        void spaceJoinedBooleanOptionIsRejectedInsteadOfHanging() {
                for (String token : List.of("--parallel-load true", "-par true",
                                "--pg-catalog-cache-rows\ttrue")) {
                        var args = new CliArgs();
                        var exception = Assertions.assertThrows(CmdLineException.class,
                                        () -> args.parse(new String[] { token, "source", "target" }),
                                        token);
                        Assertions.assertEquals(spaceJoinedMessage(token), exception.getMessage());
                }
        }

        // A value option is not caught by the spin: args4j assigns the whole
        // token, option name included, to the value and exits successfully.
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        void spaceJoinedValueOptionIsRejectedInsteadOfSilentlySwallowed() {
                for (String token : List.of("--pg-catalog-cache-dir cache-dir",
                                "--jdbc-fetch-size 64", "-o output")) {
                        var args = new CliArgs();
                        var exception = Assertions.assertThrows(CmdLineException.class,
                                        () -> args.parse(new String[] { token, "source", "target" }),
                                        token);
                        Assertions.assertAll(token,
                                        () -> Assertions.assertEquals(spaceJoinedMessage(token),
                                                        exception.getMessage()),
                                        () -> Assertions.assertNull(args.getPgCatalogCacheDir()),
                                        () -> Assertions.assertNull(args.getOutputTarget()));
                }
        }

        // The hardened value delimiter alone must already make the parser
        // terminate, without help from the known-option pre-check.
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        void spaceJoinedUnknownOptionIsRejectedByTheParserItself() {
                var args = new CliArgs();

                var exception = Assertions.assertThrows(CmdLineException.class,
                                () -> args.parse(new String[] {
                                                "--no-such-option true", "source", "target"
                                }));

                Assertions.assertEquals("\"--no-such-option true\" is not a valid option",
                                exception.getMessage());
        }

        // Only whitespace is rejected: '=' remains the in-token delimiter, and
        // a value that merely contains whitespace stays untouched.
        @Test
        void equalsJoinedOptionsAndWhitespaceValuesKeepWorking(@TempDir Path tempDir)
                        throws CmdLineException {
                var args = new CliArgs();
                Assertions.assertTrue(args.parse(new String[] {
                                "--jdbc-fetch-size=64", "--pg-catalog-cache-dir=" + tempDir,
                                "source", "target"
                }));
                Assertions.assertEquals(64, args.getJdbcFetchSize());
                Assertions.assertEquals(tempDir.toString(), args.getPgCatalogCacheDir());

                var spacedValue = new CliArgs();
                Assertions.assertTrue(spacedValue.parse(new String[] {
                                "-o", "out file.sql", "source", "target"
                }));
                Assertions.assertEquals("out file.sql", spacedValue.getOutputTarget());
        }

        @Test
        void spaceJoinedOptionMessageIsLocalizedInEnglishAndRussian() {
                var english = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages", Locale.ENGLISH);
                var russian = ResourceBundle.getBundle(
                                "org.pgcodekeeper.cli.localizations.messages",
                                Locale.forLanguageTag("ru-RU"));

                Assertions.assertAll(
                                () -> Assertions.assertEquals(
                                                "Argument \"%s\" joins option \"%s\" and its value in one "
                                                                + "token: pass them as separate arguments, or "
                                                                + "join them with '='.",
                                                english.getString(
                                                                "CliArgs_error_space_joined_option")),
                                () -> Assertions.assertEquals(
                                                "Аргумент \"%s\" объединяет опцию \"%s\" и её значение "
                                                                + "в одном токене: передайте их отдельными "
                                                                + "аргументами или соедините через '='.",
                                                russian.getString(
                                                                "CliArgs_error_space_joined_option")));
        }

        private static String spaceJoinedMessage(String token) {
                String name = token.split("\\s", 2)[0];
                return "Argument \"" + token + "\" joins option \"" + name
                                + "\" and its value in one token: pass them as separate arguments,"
                                + " or join them with '='.";
        }

        @Test
        void objectReferenceCollectionIsEnabledByDefault() {
                Assertions.assertTrue(new CliArgs().isCollectObjectReferences());
        }

        @Test
        void shallowCopyPreservesDisabledObjectReferenceCollection() {
                var args = new CliArgs();
                args.setCollectObjectReferences(false);

                Assertions.assertFalse(args.shallowCopy().isCollectObjectReferences());
        }

        /**
         * Both relaxations are opt-in and both must survive {@code shallowCopy()}:
         * BATCH mode builds every output from a copy, so a flag lost there is a
         * flag silently absent from the generated script.
         */
        @Test
        void onlySuppressionAndColumnStatisticsAreOptInAndSurviveShallowCopy()
                        throws CmdLineException {
                var bare = new CliArgs();
                Assertions.assertTrue(bare.parse(new String[] { "source", "target" }));
                Assertions.assertFalse(bare.isNoAlterTableOnly());
                Assertions.assertFalse(bare.isIgnoreColumnStatistics());

                var args = new CliArgs();
                Assertions.assertTrue(args.parse(new String[] {
                                "--no-alter-table-only", "--ignore-column-statistics",
                                "source", "target"
                }));
                Assertions.assertTrue(args.isNoAlterTableOnly());
                Assertions.assertTrue(args.isIgnoreColumnStatistics());

                var copy = args.shallowCopy();
                Assertions.assertTrue(copy.isNoAlterTableOnly());
                Assertions.assertTrue(copy.isIgnoreColumnStatistics());
        }

        @Test
        @SuppressWarnings({ "rawtypes", "unchecked" })
        void shallowCopyDefensivelyCopiesEveryListField() throws IllegalAccessException {
                var source = new CliArgs();
                List<java.lang.reflect.Field> listFields = Arrays.stream(CliArgs.class.getDeclaredFields())
                                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                                .filter(field -> List.class.isAssignableFrom(field.getType()))
                                .peek(field -> field.setAccessible(true))
                                .toList();
                Assertions.assertFalse(listFields.isEmpty());
                for (var field : listFields) {
                        ((List) field.get(source)).add(field.getName());
                }

                var copy = source.shallowCopy();

                for (var field : listFields) {
                        List sourceList = (List) field.get(source);
                        List copiedList = (List) field.get(copy);
                        Assertions.assertNotSame(sourceList, copiedList, field.getName());
                        Assertions.assertEquals(List.of(field.getName()), copiedList, field.getName());

                        sourceList.add(new Object());
                        Assertions.assertEquals(1, copiedList.size(), field.getName());
                        copiedList.clear();
                        Assertions.assertEquals(2, sourceList.size(), field.getName());
                }
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = ';', value = {
                        "--mode PARSE;" +
                                        "Please specify SOURCE.",

                        "--mode PARSE -o;" +
                                        "Option \"--output (-o)\" takes an operand",

                        "--mode PARSE -s filename -t filename -o filename;" +
                                        "--target (-t) cannot be used with --mode PARSE option",

                        "--mode graph --graph-name public.test jdbc:postgresql:q jdbc:postgresql:q2;"
                                        + "--target (-t) cannot be used with --mode GRAPH option",

                        "--mode graph --graph-name test;"
                                        + "Please specify SOURCE.",

                        "--mode PARSE --graph-filter-object COLUMN jdbc:postgresql:q;"
                                        + "--graph-filter-object cannot be used with --mode PARSE option",

                        "jdbc:postgresql:q;"
                                        + "Please specify both SOURCE and DEST.",

                        "jdbc:postgresql:q jdbc:postgresql:q2 -X -C;"
                                        + "--concurrently-mode (-C) cannot be used with the option(s) --add-transaction (-X) for PostgreSQL.",

                        "-r filename filename;"
                                        + "Script can be applied only to database.",

                        "-R filename filename filename;"
                                        + "Option --run-on (-R) must specify JDBC connection string.",

                        "filename filename --simplify-views --db-type MS;"
                                        + "--simplify-views cannot be used with --db-type MS option",

                        "jdbc:postgresql:q jdbc:postgresql:q2 --cluster-name test;"
                                        + "--cluster-name cannot be used with --db-type PG option",

                        "--mode PARSE --additional-dependencies .pgcodekeeperdeps;"
                                        + "--additional-dependencies cannot be used with --mode PARSE option",

                        "--mode PARSE --use-actual-syntax;"
                                        + "--use-actual-syntax cannot be used with --mode PARSE option",

                        "--mode GRAPH --simplify-not-null;"
                                        + "--simplify-not-null cannot be used with --mode GRAPH option",

                        "--mode PARSE --ignore-sequence-cache;"
                                        + "--ignore-sequence-cache cannot be used with --mode PARSE option",

                        "--mode PARSE --no-alter-table-only;"
                                        + "--no-alter-table-only cannot be used with --mode PARSE option",

                        "--mode PARSE --ignore-column-statistics;"
                                        + "--ignore-column-statistics cannot be used with --mode PARSE option",

                        "--db-type MS --simplify-not-null;"
                                        + "--simplify-not-null cannot be used with --db-type MS option",

                        "--jdbc-fetch-size -1 filename filename;"
                                        + "JDBC fetch size must not be negative.",

                        // rollback switches conflict with the positive flags
                        // and with the options that need the disabled path
                        "--parallel-load --no-parallel-load filename filename;"
                                        + "option \"--parallel-load\" cannot be used with the option(s) [--no-parallel-load]",

                        "--pg-routine-body-hash-first --pg-routine-body-no-hash-first filename filename;"
                                        + "option \"--pg-routine-body-hash-first\" cannot be used with the option(s) [--pg-routine-body-no-hash-first]",

                        "--no-parallel-load --pg-routine-body-hash-first filename filename;"
                                        + "option \"--pg-routine-body-hash-first\" cannot be used with the option(s) [--no-parallel-load]",

                        // an explicit default value still counts as usage of the option
                        "--pg-routine-body-no-hash-first --pg-routine-body-residual-batch-count 256 filename filename;"
                                        + "option \"--pg-routine-body-residual-batch-count\" cannot be used with the option(s) [--pg-routine-body-no-hash-first]",

                        "--pg-routine-body-no-hash-first --pg-routine-body-residual-batch-bytes 4096 filename filename;"
                                        + "option \"--pg-routine-body-residual-batch-bytes\" cannot be used with the option(s) [--pg-routine-body-no-hash-first]",

                        "--pg-routine-body-no-hash-first --pg-catalog-cache-dir cache-dir filename filename;"
                                        + "option \"--pg-catalog-cache-dir\" cannot be used with the option(s) [--pg-routine-body-no-hash-first]",

                        "--no-parallel-load --pg-routine-body-residual-batch-count 17 filename filename;"
                                        + "option \"--pg-routine-body-residual-batch-count\" cannot be used with the option(s) [--no-parallel-load]",

                        "--no-parallel-load --pg-routine-body-residual-batch-bytes 4096 filename filename;"
                                        + "option \"--pg-routine-body-residual-batch-bytes\" cannot be used with the option(s) [--no-parallel-load]",

                        "--no-parallel-load --pg-catalog-cache-dir cache-dir filename filename;"
                                        + "option \"--pg-catalog-cache-dir\" cannot be used with the option(s) [--no-parallel-load]",

                        "--mode PARSE --pg-routine-body-no-skip-matched-analysis -o output source;"
                                        + "--pg-routine-body-no-skip-matched-analysis cannot be used with --mode PARSE option",

                        "--db-type MS --pg-routine-body-no-skip-matched-analysis filename filename;"
                                        + "--pg-routine-body-no-skip-matched-analysis cannot be used with --db-type MS option",

                        "--mode PARSE --pg-routine-body-no-hash-first -o output source;"
                                        + "--pg-routine-body-no-hash-first cannot be used with --mode PARSE option",

                        "--db-type MS --pg-routine-body-no-hash-first filename filename;"
                                        + "--pg-routine-body-no-hash-first cannot be used with --db-type MS option",

                        // direct mode/db-type checks that replace the relaxed
                        // depends = --pg-routine-body-hash-first restriction
                        "--mode GRAPH --pg-routine-body-residual-batch-count 17 source;"
                                        + "--pg-routine-body-residual-batch-count cannot be used with --mode GRAPH option",

                        "--db-type MS --pg-routine-body-residual-batch-count 17 filename filename;"
                                        + "--pg-routine-body-residual-batch-count cannot be used with --db-type MS option",

                        "--db-type CH --pg-routine-body-residual-batch-bytes 4096 filename filename;"
                                        + "--pg-routine-body-residual-batch-bytes cannot be used with --db-type CH option",

                        "--mode PARSE --pg-catalog-cache-dir cache-dir -o output source;"
                                        + "--pg-catalog-cache-dir cannot be used with --mode PARSE option",

                        "--db-type MS --pg-catalog-cache-dir cache-dir filename filename;"
                                        + "--pg-catalog-cache-dir cannot be used with --db-type MS option",

                        "--db-type MS --pg-parallel-catalog-readers 4 filename filename;"
                                        + "--pg-parallel-catalog-readers cannot be used with --db-type MS option",

                        "--db-type CH --pg-parallel-catalog-readers 4 filename filename;"
                                        + "--pg-parallel-catalog-readers cannot be used with --db-type CH option",

                        // an explicit 0 is still usage of a PostgreSQL-only option
                        "--db-type MS --pg-parallel-catalog-readers 0 filename filename;"
                                        + "--pg-parallel-catalog-readers cannot be used with --db-type MS option",

                        "--mode PARSE --parallel-load --pg-routine-body-hash-first -o output source;"
                                        + "--pg-routine-body-hash-first cannot be used with --mode PARSE option",

                        "--db-type MS --parallel-load --pg-routine-body-hash-first filename filename;"
                                        + "--pg-routine-body-hash-first cannot be used with --db-type MS option",

                        "--db-type CH --parallel-load --pg-routine-body-hash-first filename filename;"
                                        + "--pg-routine-body-hash-first cannot be used with --db-type CH option",

                        "--parallel-load --pg-routine-body-hash-first --pg-routine-body-residual-batch-count 0 filename filename;"
                                        + "PostgreSQL residual routine body batch count must be positive.",

                        "--parallel-load --pg-routine-body-hash-first --pg-routine-body-residual-batch-count -1 filename filename;"
                                        + "PostgreSQL residual routine body batch count must be positive.",

                        "--parallel-load --pg-routine-body-hash-first --pg-routine-body-residual-batch-bytes 0 filename filename;"
                                        + "PostgreSQL residual routine body batch byte budget must be positive.",

                        "--parallel-load --pg-routine-body-hash-first --pg-routine-body-residual-batch-bytes -1 filename filename;"
                                        + "PostgreSQL residual routine body batch byte budget must be positive.",

                        "--parallel-load --pg-routine-body-hash-first --pg-catalog-cache-max-mb 64 filename filename;"
                                        + "option \"--pg-catalog-cache-max-mb\" requires the option(s) [--pg-catalog-cache-dir]",

                        "--parallel-load --pg-routine-body-hash-first --pg-catalog-cache-rows filename filename;"
                                        + "option \"--pg-catalog-cache-rows\" requires the option(s) [--pg-catalog-cache-dir]",

                        "--parallel-load --pg-routine-body-hash-first --pg-catalog-cache-dir cache-dir --pg-catalog-cache-max-mb 0 filename filename;"
                                        + "PostgreSQL catalog cache size limit must be positive.",

                        "--parallel-load --pg-routine-body-hash-first --pg-catalog-cache-dir cache-dir --pg-catalog-cache-max-mb -1 filename filename;"
                                        + "PostgreSQL catalog cache size limit must be positive.",

                        "--mode BATCH;"
                                        + "Please specify --batch-manifest in BATCH mode.",

                        "--batch-manifest batch.json filename filename;"
                                        + "--batch-manifest cannot be used with --mode DIFF option",

                        "--mode BATCH --batch-manifest batch.json filename;"
                                        + "--source (-s) cannot be used with --mode BATCH option",

                        "--mode BATCH --batch-manifest batch.json -X;"
                                        + "--add-transaction (-X) cannot be used with --mode BATCH option"
        })
        void badArgsTest(String arguments, String message) {
                String[] args = arguments.split(" ");
                CliArgs cliArgs = new CliArgs();
                // Both diagnostics repeat the argument line on purpose. The console
                // failure summary identifies the case by method and line only, so a
                // message-less failure leaves the reader counting CsvSource entries
                // to find the offending row.
                try {
                        cliArgs.parse(args);
                        Assertions.fail("Arguments were accepted but must have been rejected: ["
                                        + arguments + "]; expected CmdLineException: " + message);
                } catch (CmdLineException e) {
                        Assertions.assertEquals(message, e.getMessage(),
                                        () -> "Wrong rejection message for arguments: [" + arguments + ']');
                }
        }
}
