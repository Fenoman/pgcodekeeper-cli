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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.database.pg.routine.PgRoutineBodyAnalysisStats;
import org.pgcodekeeper.core.settings.ProjectFileFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end BATCH mode runs: every batch output must be byte-identical to a
 * standalone DIFF run invoked with {@code common + output args}.
 */
@Isolated("reads and resets the process-wide routine body analysis counters")
class BatchDiffTest {

    @TempDir
    private Path tempDir;

    @AfterEach
    void resetRoutineBodyAnalysisStats() {
        PgRoutineBodyAnalysisStats.reset();
    }

    @Test
    void batchOutputsAreByteIdenticalToStandaloneRuns()
            throws IOException, URISyntaxException {
        String newSrc = resource("ignore_new.sql");
        String oldSrc = resource("ignore_old.sql");
        String blackIgnore = resource("black.ignore");
        String whiteIgnore = resource("white.ignore");

        Path standalonePlain = tempDir.resolve("standalone_plain.sql");
        Path standaloneFiltered = tempDir.resolve("standalone_filtered.sql");
        Assertions.assertTrue(Application.process(new String[]{
                "-s", newSrc, "-t", oldSrc, "-o", standalonePlain.toString()}));
        Assertions.assertTrue(Application.process(new String[]{
                "-s", newSrc, "-t", oldSrc,
                "-I", blackIgnore, "-I", whiteIgnore, "-X",
                "-o", standaloneFiltered.toString()}));

        Path batchPlain = tempDir.resolve("batch_plain.sql");
        Path batchFiltered = tempDir.resolve("batch_filtered.sql");
        Path manifest = writeManifest("""
                {
                  "common": [%s, %s, %s, %s],
                  "outputs": [
                    {"name": "plain", "args": [%s, %s]},
                    {"name": "filtered",
                     "args": [%s, %s, %s, %s, %s, %s, %s]}
                  ]
                }
                """.formatted(
                json("-s"), json(newSrc), json("-t"), json(oldSrc),
                json("-o"), json(batchPlain.toString()),
                json("-o"), json(batchFiltered.toString()),
                json("-I"), json(blackIgnore), json("-I"), json(whiteIgnore),
                json("-X")));

        Assertions.assertTrue(Application.process(new String[]{
                "--mode", "BATCH", "--batch-manifest", manifest.toString()}));

        assertSameBytes(standalonePlain, batchPlain);
        assertSameBytes(standaloneFiltered, batchFiltered);
        Assertions.assertNotEquals(
                Files.readString(batchPlain), Files.readString(batchFiltered),
                "outputs must differ, otherwise the ignore list had no effect");
    }

    @Test
    void noCheckFunctionBodiesInCommonMatchesStandaloneRoutineAnalysis() throws IOException {
        Path oldSrc = Files.writeString(tempDir.resolve("routine_old.sql"),
                lateBoundRoutineFixture("integer"));
        Path newSrc = Files.writeString(tempDir.resolve("routine_new.sql"),
                lateBoundRoutineFixture("bigint"));

        Path standalone = tempDir.resolve("routine_standalone.sql");
        PgRoutineBodyAnalysisStats.reset();
        Assertions.assertTrue(Application.process(new String[] {
                "-s", newSrc.toString(), "-t", oldSrc.toString(), "-F",
                "-o", standalone.toString()
        }));
        Assertions.assertAll(
                () -> Assertions.assertEquals(0,
                        PgRoutineBodyAnalysisStats.getSkippedBodies()),
                () -> Assertions.assertEquals(4,
                        PgRoutineBodyAnalysisStats.getParsedBodies()));

        Path batch = tempDir.resolve("routine_batch.sql");
        Path manifest = writeManifest("""
                {
                  "common": [%s, %s, %s, %s, "-F"],
                  "outputs": [
                    {"name": "routine-analysis", "args": [%s, %s]}
                  ]
                }
                """.formatted(
                json("-s"), json(newSrc.toString()),
                json("-t"), json(oldSrc.toString()),
                json("-o"), json(batch.toString())));

        PgRoutineBodyAnalysisStats.reset();
        Assertions.assertTrue(Application.process(new String[] {
                "--mode", "BATCH", "--batch-manifest", manifest.toString()
        }));
        Assertions.assertAll(
                () -> Assertions.assertEquals(0,
                        PgRoutineBodyAnalysisStats.getSkippedBodies()),
                () -> Assertions.assertEquals(4,
                        PgRoutineBodyAnalysisStats.getParsedBodies()));

        assertSameBytes(standalone, batch);
        String script = Files.readString(batch);
        Assertions.assertAll(
                () -> Assertions.assertTrue(
                        script.contains("SET check_function_bodies = false"), script),
                () -> Assertions.assertTrue(
                        script.contains("DROP FUNCTION public.f_sql();"), script),
                () -> Assertions.assertTrue(
                        script.contains("DROP FUNCTION public.f_plpgsql();"), script),
                () -> Assertions.assertTrue(script.contains(
                        "depends on the COLUMN: public.body_dependency.id"), script));
    }

    @Test
    void failedSafeModeOutputDoesNotStopOthersAndExitsNonZero()
            throws IOException, URISyntaxException {
        String newSrc = resource("drop_column_new.sql");
        String oldSrc = resource("drop_column_original.sql");

        Path guardedFile = tempDir.resolve("guarded.sql");
        Path plainFile = tempDir.resolve("plain.sql");
        Path manifest = writeManifest("""
                {
                  "common": [%s, %s, %s, %s],
                  "outputs": [
                    {"name": "guarded", "args": [%s, %s, "-S"]},
                    {"name": "plain", "args": [%s, %s]}
                  ]
                }
                """.formatted(
                json("-s"), json(newSrc), json("-t"), json(oldSrc),
                json("-o"), json(guardedFile.toString()),
                json("-o"), json(plainFile.toString())));

        String stderr;
        boolean result;
        PrintStream originalErr = System.err;
        var errBytes = new ByteArrayOutputStream();
        try (PrintStream errStream = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            System.setErr(errStream);
            result = Application.process(new String[]{
                    "--mode", "BATCH", "--batch-manifest", manifest.toString()});
            stderr = errBytes.toString(StandardCharsets.UTF_8);
        } finally {
            System.setErr(originalErr);
        }

        Assertions.assertFalse(result, "batch with a failed output must exit non-zero");
        Assertions.assertEquals(
                "-- Script contains dangerous statements: DROP_COLUMN."
                        + " Use --allow-danger-ddl to override.\n",
                Files.readString(guardedFile).replace("\r\n", "\n"),
                "failed safe-mode output must contain the danger notice only");
        Assertions.assertTrue(Files.readString(plainFile).contains("DROP COLUMN"),
                "remaining outputs must still be produced");
        Assertions.assertTrue(stderr.contains(
                        "batch output 'guarded': FAILED - dangerous statements: DROP_COLUMN"),
                stderr);
        Assertions.assertTrue(stderr.contains(
                "batch output 'plain': OK -> " + plainFile), stderr);
    }

    @Test
    void projectSourceWithAutoLoadedIgnoreFileMatchesStandalone()
            throws IOException {
        Path project = tempDir.resolve("project");
        writeProjectFile(project, "SCHEMA/public/public.sql", "CREATE SCHEMA public;");
        writeProjectFile(project, "SCHEMA/public/TABLE/visible_table.sql",
                "CREATE TABLE public.visible_table (id integer);");
        writeProjectFile(project, "SCHEMA/public/TABLE/hidden_table.sql",
                "CREATE TABLE public.hidden_table (id integer);");
        writeProjectFile(project, ".pgcodekeeperignore", "SHOW ALL\nHIDE NONE hidden_table");

        Path extraIgnore = tempDir.resolve("extra.ignore");
        Files.writeString(extraIgnore, "SHOW ALL\nHIDE NONE visible_table\n");
        Path emptyDump = tempDir.resolve("empty.sql");
        Files.writeString(emptyDump, "");

        Path standaloneMain = tempDir.resolve("standalone_main.sql");
        Path standaloneExtra = tempDir.resolve("standalone_extra.sql");
        Assertions.assertTrue(Application.process(new String[]{
                "-s", project.toString(), "-t", emptyDump.toString(),
                "-o", standaloneMain.toString()}));
        Assertions.assertTrue(Application.process(new String[]{
                "-s", project.toString(), "-t", emptyDump.toString(),
                "-I", extraIgnore.toString(), "-o", standaloneExtra.toString()}));

        Path batchMain = tempDir.resolve("batch_main.sql");
        Path batchExtra = tempDir.resolve("batch_extra.sql");
        Path manifest = writeManifest("""
                {
                  "common": [%s, %s, %s, %s],
                  "outputs": [
                    {"name": "main", "args": [%s, %s]},
                    {"name": "extra", "args": [%s, %s, %s, %s]}
                  ]
                }
                """.formatted(
                json("-s"), json(project.toString()), json("-t"), json(emptyDump.toString()),
                json("-o"), json(batchMain.toString()),
                json("-o"), json(batchExtra.toString()),
                json("-I"), json(extraIgnore.toString())));

        Assertions.assertTrue(Application.process(new String[]{
                "--mode", "BATCH", "--batch-manifest", manifest.toString()}));

        assertSameBytes(standaloneMain, batchMain);
        assertSameBytes(standaloneExtra, batchExtra);

        String mainScript = Files.readString(batchMain);
        Assertions.assertTrue(mainScript.contains("visible_table"), mainScript);
        Assertions.assertFalse(mainScript.contains("hidden_table"),
                "auto-loaded project ignore file must apply to batch outputs");
        Assertions.assertFalse(Files.readString(batchExtra).contains("visible_table"),
                "per-output ignore list must apply on top of the project ignore file");
    }

    @Test
    void projectFileFilterInCommonMatchesStandaloneDiff() throws IOException {
        Path oldProject = tempDir.resolve("old-filtered-project");
        Path newProject = tempDir.resolve("new-filtered-project");
        writeProjectFile(oldProject, "SCHEMA/public/public.sql", "CREATE SCHEMA public;");
        writeProjectFile(newProject, "SCHEMA/public/public.sql", "CREATE SCHEMA public;");
        writeProjectFile(oldProject, "SCHEMA/public/TABLE/included_exception.sql",
                "CREATE TABLE public.included_exception (id integer);");
        writeProjectFile(newProject, "SCHEMA/public/TABLE/included_exception.sql",
                "CREATE TABLE public.included_exception (id integer, added text);");
        writeProjectFile(oldProject, "SCHEMA/public/TABLE/old_broken.sql",
                "THIS IS INVALID SQL;");
        writeProjectFile(newProject, "SCHEMA/public/TABLE/new_broken.sql",
                "THIS IS INVALID SQL;");
        writeProjectFile(newProject, "SCHEMA/public/TABLE/unwanted.sql",
                "CREATE TABLE public.unwanted (id integer);");

        Path filter = Files.writeString(tempDir.resolve("batch-project.filter"), """
                EXCLUDE REGEX ^SCHEMA/public/TABLE/.*\\.sql$
                INCLUDE PATH SCHEMA/public/TABLE/included_exception.sql
                """);
        Path standalone = tempDir.resolve("standalone_project_filter.sql");
        Assertions.assertTrue(Application.process(new String[] {
                "-s", newProject.toString(), "-t", oldProject.toString(),
                "--project-file-filter", filter.toString(),
                "-o", standalone.toString()
        }));

        Path batch = tempDir.resolve("batch_project_filter.sql");
        Path manifest = writeManifest("""
                {
                  "common": [%s, %s, %s, %s, %s, %s],
                  "outputs": [
                    {"name": "filtered", "args": [%s, %s]}
                  ]
                }
                """.formatted(
                json("-s"), json(newProject.toString()),
                json("-t"), json(oldProject.toString()),
                json("--project-file-filter"), json(filter.toString()),
                json("-o"), json(batch.toString())));

        Assertions.assertTrue(Application.process(new String[] {
                "--mode", "BATCH", "--batch-manifest", manifest.toString()
        }));

        assertSameBytes(standalone, batch);
        String script = Files.readString(batch);
        Assertions.assertAll(
                () -> Assertions.assertTrue(script.contains("included_exception"), script),
                () -> Assertions.assertFalse(script.contains("unwanted"), script),
                () -> Assertions.assertFalse(script.contains("broken"), script));
    }

    @Test
    void projectFileFilterIsCompiledOnceAndSharedByEveryOutput() throws Exception {
        Path filter = Files.writeString(tempDir.resolve("shared-project.filter"),
                "EXCLUDE PATH SCHEMA/public/TABLE/hidden.sql\n");
        Path firstOutput = tempDir.resolve("first.sql");
        Path secondOutput = tempDir.resolve("second.sql");
        Path manifestPath = writeManifest("""
                {
                  "common": ["-s", "new-project", "-t", "old-project",
                             "--project-file-filter", %s],
                  "outputs": [
                    {"name": "first", "args": ["-o", %s]},
                    {"name": "second", "args": ["-o", %s, "-X"]}
                  ]
                }
                """.formatted(json(filter.toString()), json(firstOutput.toString()),
                json(secondOutput.toString())));
        BatchManifest manifest = BatchManifest.read(manifestPath);
        CliArgs baseArgs = new CliArgs();
        Assertions.assertTrue(baseArgs.parse(manifest.commonArgs().toArray(new String[0])));
        ProjectFileFilter compiledFilter = baseArgs.getProjectFileFilter();
        Assertions.assertNotSame(ProjectFileFilter.ALLOW_ALL, compiledFilter);

        Files.delete(filter);
        var tasks = new PgBatchDiffCli(new CliArgs()).createOutputTasks(manifest, baseArgs);

        Assertions.assertEquals(2, tasks.size());
        Assertions.assertFalse(tasks.get(0).args().isAddTransaction());
        Assertions.assertTrue(tasks.get(1).args().isAddTransaction());
        for (var task : tasks) {
            Assertions.assertAll(
                    () -> Assertions.assertEquals(filter.toString(),
                            task.args().getProjectFileFilterPath()),
                    () -> Assertions.assertSame(compiledFilter,
                            task.args().getProjectFileFilter()));
        }
    }

    @Test
    void outputWithoutFileTargetIsRejected() throws IOException {
        Path manifest = writeManifest("""
                {
                  "common": ["-s", "new.sql", "-t", "old.sql"],
                  "outputs": [{"name": "main", "args": ["-X"]}]
                }
                """);

        String stderr = runExpectingFailure(manifest);
        Assertions.assertTrue(stderr.contains(
                "batch output \"main\" must specify -o (--output)"), stderr);
    }

    @Test
    void duplicateOutputPathsAreRejected() throws IOException {
        Path output = tempDir.resolve("same.sql");
        Path manifest = writeManifest("""
                {
                  "common": ["-s", "new.sql", "-t", "old.sql"],
                  "outputs": [
                    {"name": "first", "args": [%s, %s]},
                    {"name": "second", "args": [%s, %s]}
                  ]
                }
                """.formatted(json("-o"), json(output.toString()),
                json("-o"), json(output.toString())));

        String stderr = runExpectingFailure(manifest);
        Assertions.assertTrue(stderr.contains(
                        "batch output \"second\" writes to the same file as another output"),
                stderr);
    }

    @Test
    void invalidCommonArgsAreReportedWithSectionContext() throws IOException {
        Path manifest = writeManifest("""
                {
                  "common": ["-s", "new.sql"],
                  "outputs": [{"name": "main", "args": ["-o", "out.sql"]}]
                }
                """);

        String stderr = runExpectingFailure(manifest);
        Assertions.assertTrue(stderr.contains("batch manifest \"common\" section:"), stderr);
    }

    private String runExpectingFailure(Path manifest) {
        PrintStream originalErr = System.err;
        var errBytes = new ByteArrayOutputStream();
        try (PrintStream errStream = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            System.setErr(errStream);
            boolean result = Application.process(new String[]{
                    "--mode", "BATCH", "--batch-manifest", manifest.toString()});
            Assertions.assertFalse(result, "invalid batch run must fail");
            return errBytes.toString(StandardCharsets.UTF_8);
        } finally {
            System.setErr(originalErr);
        }
    }

    private Path writeManifest(String json) throws IOException {
        Path manifest = Files.createTempFile(tempDir, "batch", ".json");
        Files.writeString(manifest, json);
        return manifest;
    }

    private static void writeProjectFile(Path projectRoot, String relativePath, String sql)
            throws IOException {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, sql + '\n');
    }

    private static String lateBoundRoutineFixture(String columnType) {
        return """
                CREATE TABLE public.body_dependency (id %s);

                CREATE FUNCTION public.f_sql() RETURNS integer
                    LANGUAGE sql
                    AS $$ SELECT id::integer FROM public.body_dependency LIMIT 1 $$;

                CREATE FUNCTION public.f_plpgsql() RETURNS integer
                    LANGUAGE plpgsql
                    AS $$ BEGIN
                        RETURN (SELECT id::integer FROM public.body_dependency LIMIT 1);
                    END $$;
                """.formatted(columnType);
    }

    private static void assertSameBytes(Path expected, Path actual) throws IOException {
        Assertions.assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(actual),
                "%s and %s must be byte-identical".formatted(expected, actual));
    }

    private String resource(String fileName) throws URISyntaxException {
        return TestUtils.getPathToResource(DiffTest.class, fileName).toString();
    }

    private static String json(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
