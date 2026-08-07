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
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The CLI end of the dependencies-file fail-fast.
 * <p>
 * A broken {@code .pgcodekeeperdependencies} answered with an empty list is
 * indistinguishable from a project that carries no such file at all. The edges
 * of that file only add ordering, so nothing fails and nothing is printed: the
 * migration script comes out in a different order, or short of statements, and
 * the run exits 0. That is the shape this test holds shut - the core-side
 * contract is held by {@code DependenciesReaderFailFastTest}, and this one
 * holds that the refusal actually reaches the operator instead of dying
 * somewhere in between.
 */
class AdditionalDependenciesFailFastTest {

    /**
     * The middle line writes the arrow as {@code =>}, which this grammar has no
     * token for. ANTLR recovery swallows it and hands back a full list anyway,
     * so nothing downstream could ever notice.
     */
    private static final String BROKEN_DEPS = """
            TABLE public.t1 -> TABLE public.t2;
            TABLE public.t2 => TABLE public.t3;
            """;

    private static final String NEW_SQL = """
            CREATE TABLE public.t1 (id integer);
            CREATE TABLE public.t2 (id integer);
            """;

    private static final String OLD_SQL = "CREATE TABLE public.t1 (id integer);\n";

    @Test
    void aBrokenDependenciesFileFailsTheRun(@TempDir Path dir) throws Exception {
        Path deps = Files.writeString(dir.resolve("broken.pgcodekeeperdependencies"), BROKEN_DEPS);

        CliResult result = runDiff(dir, "--additional-dependencies", deps.toString());

        Assertions.assertFalse(result.success(),
                "a broken --additional-dependencies file must stop the run, not silently drop its edges");
        Assertions.assertTrue(result.output().contains(deps.toString()),
                () -> "and the diagnostic must name the file, got:\n" + result.output());
    }

    /**
     * The half that must not change: a readable file still produces its script.
     * Without this the fix would be indistinguishable from one that refuses
     * every dependencies file.
     */
    @Test
    void aReadableDependenciesFileStillRuns(@TempDir Path dir) throws Exception {
        Path deps = Files.writeString(dir.resolve("good.pgcodekeeperdependencies"),
                "TABLE public.t1 -> TABLE public.t2;\n");

        CliResult result = runDiff(dir, "--additional-dependencies", deps.toString());

        Assertions.assertTrue(result.success(),
                () -> "a readable dependencies file must still produce a script, got:\n" + result.output());
    }

    private static CliResult runDiff(Path dir, String... extraArgs) throws Exception {
        Path newSql = Files.writeString(dir.resolve("new.sql"), NEW_SQL);
        Path oldSql = Files.writeString(dir.resolve("old.sql"), OLD_SQL);

        String[] args = new String[extraArgs.length + 2];
        System.arraycopy(extraArgs, 0, args, 0, extraArgs.length);
        args[extraArgs.length] = newSql.toString();
        args[extraArgs.length + 1] = oldSql.toString();

        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             PrintStream ps = new PrintStream(baos)) {
            System.setOut(ps);
            System.setErr(ps);

            boolean success = Application.process(args);

            System.out.flush();
            return new CliResult(success, baos.toString().replace("\r\n", "\n"));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private record CliResult(boolean success, String output) {
    }
}
