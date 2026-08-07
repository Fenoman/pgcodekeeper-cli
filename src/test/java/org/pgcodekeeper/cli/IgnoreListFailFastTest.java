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
import org.pgcodekeeper.core.database.base.loader.AbstractProjectLoader;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A syntactically broken ignore list file must fail the whole CLI run with a
 * localized diagnostic and a non-zero exit. The fixture verifies that one
 * malformed regular-expression rule invalidates the complete file.
 */
class IgnoreListFailFastTest {

    private static final String PARSE_ERROR_LINE_13 =
            "Failed to parse ignore list file: %s line 13:15 mismatched input '.' expecting {<EOF>, NewLine}\n"
                    + "Use -E to see exception stacktrace\n";

    private static final String BROKEN_RULE = "HIDE REGEX xpo.* type=TABLE";

    @Test
    void brokenIgnoreListFailsDiffRunWithLocalizedMessage() throws Exception {
        Path broken = TestUtils.getPathToResource(getClass(), "broken_regex.ignore");

        CliResult result = runDiff("-I", broken.toString());

        Assertions.assertFalse(result.success(), "diff run with a broken -I file must fail");
        Assertions.assertEquals(PARSE_ERROR_LINE_13.formatted(broken), result.output());
    }

    @Test
    void brokenIgnoreSchemaListFailsDiffRunWithLocalizedMessage() throws Exception {
        Path broken = TestUtils.getPathToResource(getClass(), "broken_regex.ignore");

        CliResult result = runDiff("--ignore-schema", broken.toString());

        Assertions.assertFalse(result.success(), "diff run with a broken --ignore-schema file must fail");
        Assertions.assertEquals(PARSE_ERROR_LINE_13.formatted(broken), result.output());
    }

    @Test
    void brokenAutoLoadedIgnoreFileFailsProjectLoad(@TempDir Path projectDir) throws Exception {
        Path autoLoaded = writeBrokenList(projectDir.resolve(AbstractProjectLoader.IGNORE_FILE));

        CliResult result = runDiffWithProject(projectDir);

        Assertions.assertFalse(result.success(),
                "run with a broken auto-loaded " + AbstractProjectLoader.IGNORE_FILE + " must fail");
        Assertions.assertEquals(brokenListError(autoLoaded), result.output());
    }

    @Test
    void brokenAutoLoadedIgnoreSchemaFileFailsProjectLoad(@TempDir Path projectDir) throws Exception {
        Path autoLoaded = writeBrokenList(projectDir.resolve(AbstractProjectLoader.IGNORE_SCHEMA_FILE));

        CliResult result = runDiffWithProject(projectDir);

        Assertions.assertFalse(result.success(),
                "run with a broken auto-loaded " + AbstractProjectLoader.IGNORE_SCHEMA_FILE + " must fail");
        Assertions.assertEquals(brokenListError(autoLoaded), result.output());
    }

    private static Path writeBrokenList(Path listFile) throws Exception {
        return Files.writeString(listFile, "SHOW ALL\n" + BROKEN_RULE + "\n");
    }

    private static String brokenListError(Path listFile) {
        return "Failed to parse ignore list file: %s line 2:15 mismatched input '.' expecting {<EOF>, NewLine}\n"
                .formatted(listFile)
                + "Use -E to see exception stacktrace\n";
    }

    private static CliResult runDiff(String... extraArgs) throws Exception {
        String newPath = getSql("ignore_new.sql");
        String oldPath = getSql("ignore_old.sql");

        String[] args = new String[extraArgs.length + 2];
        System.arraycopy(extraArgs, 0, args, 0, extraArgs.length);
        args[extraArgs.length] = newPath;
        args[extraArgs.length + 1] = oldPath;

        return runApplication(args);
    }

    private static CliResult runDiffWithProject(Path projectDir) throws Exception {
        return runApplication(new String[]{projectDir.toString(), getSql("ignore_old.sql")});
    }

    private static String getSql(String resourceName) throws URISyntaxException {
        return TestUtils.getPathToResource(IgnoreListFailFastTest.class, resourceName).toString();
    }

    private static CliResult runApplication(String[] args) throws Exception {
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
