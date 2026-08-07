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
 * A source that did not load cleanly must not produce a silently incomplete
 * dependency graph.
 * <p>
 * Every other mode of this CLI stops on accumulated load errors - {@code diff}
 * and {@code parse} through {@code PgDiffCli.assertErrorsEmpty}, {@code batch}
 * through its own check - and all three honour {@code --ignore-errors}.
 * {@code --mode GRAPH} was the one mode that read none of them: a project with
 * one unreadable statement printed a graph missing whatever that statement
 * declared, with exit 0 and an empty stderr, and a script ordering a deployment
 * off that graph worked from incomplete data with nothing to notice.
 */
class GraphLoadErrorsTest {

    /**
     * A statement this grammar cannot read. {@code UNIQUE (SELECT ...)} is the
     * anchor to pick here, and it is stable by construction rather than by luck:
     * PostgreSQL's own {@code gram.y} carries the production, but its whole
     * action is {@code ereport(ERROR, FEATURE_NOT_SUPPORTED, "UNIQUE predicate
     * is not yet implemented")}, unchanged since REL9_6_0. A server that cannot
     * store the predicate cannot hand it back either, so {@code ruleutils} never
     * prints it and the parser is never obliged to read it - which is the only
     * thing that ever forces an alternative to be added.
     * <p>
     * The previous anchor, {@code IS NORMALIZED}, was the opposite kind: PG13+
     * does print it, so the grammar had to learn it, and this fixture stopped
     * failing. {@link #theFixtureIsOneThisGrammarCannotRead} is what caught that
     * - it holds the property instead of assuming it, so the class says so
     * rather than passing decoratively.
     */
    private static final String BROKEN_SQL = """
            CREATE TABLE public.good (id integer);

            CREATE TABLE public.bad (id integer CHECK (UNIQUE (SELECT 1)));
            """;

    @Test
    void theFixtureIsOneThisGrammarCannotRead(@TempDir Path dir) throws Exception {
        Path broken = writeBroken(dir);

        // the diff mode is the reference contract: it does stop on this file
        CliResult result = run(broken.toString(), broken.toString());

        Assertions.assertFalse(result.success(),
                "this file is supposed to fail the grammar, otherwise this whole class is decorative");
        Assertions.assertFalse(result.output().isEmpty(), "and the errors are supposed to be reported");
    }

    @Test
    void graphOnABrokenSourceFailsInsteadOfPrintingAnIncompleteGraph(@TempDir Path dir) throws Exception {
        Path broken = writeBroken(dir);

        CliResult result = run("--mode", "graph", broken.toString());

        Assertions.assertFalse(result.success(),
                "GRAPH over a source with load errors must fail like every other mode");
        Assertions.assertTrue(result.output().contains(broken.toString()),
                () -> "and must name the file the errors came from, got:\n" + result.output());
    }

    /**
     * The flag that makes the difference deliberate. Without this the fix would
     * be indistinguishable from one that fails on any source at all, and the
     * mode would lose the escape hatch the other three modes offer.
     */
    @Test
    void graphOnABrokenSourceStillRunsUnderIgnoreErrors(@TempDir Path dir) throws Exception {
        Path broken = writeBroken(dir);

        CliResult result = run("--mode", "graph", "--ignore-errors", broken.toString());

        Assertions.assertTrue(result.success(),
                () -> "--ignore-errors must still print the graph it can build, got:\n" + result.output());
        Assertions.assertTrue(result.output().contains("public.good"),
                () -> "and that graph must carry what did load, got:\n" + result.output());
    }

    /**
     * A source with no load errors is untouched: the mode still prints its
     * graph and still returns success.
     */
    @Test
    void graphOnACleanSourceIsUnchanged(@TempDir Path dir) throws Exception {
        Path clean = Files.writeString(dir.resolve("clean.sql"), "CREATE TABLE public.good (id integer);\n");

        CliResult result = run("--mode", "graph", clean.toString());

        Assertions.assertTrue(result.success(),
                () -> "a source that loaded cleanly must still produce its graph, got:\n" + result.output());
        Assertions.assertTrue(result.output().contains("public.good"),
                () -> "and that graph must carry the table, got:\n" + result.output());
    }

    private static Path writeBroken(Path dir) throws Exception {
        return Files.writeString(dir.resolve("broken.sql"), BROKEN_SQL);
    }

    private static CliResult run(String... args) throws Exception {
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
