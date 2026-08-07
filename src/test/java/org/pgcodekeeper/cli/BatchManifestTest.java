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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.kohsuke.args4j.CmdLineException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class BatchManifestTest {

    static {
        // asserted diagnostics are the English CLI contract
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
    }

    @TempDir
    private Path tempDir;

    @Test
    void validManifestParsesCommonArgsAndOutputsInOrder() throws Exception {
        BatchManifest manifest = read("""
                {
                  "common": ["-s", "new.sql", "-t", "old.sql", "--ignore-schema", "schemas"],
                  "outputs": [
                    {"name": "main", "args": ["-o", "main.sql", "-X"]},
                    {"name": "audit", "args": ["-o", "audit.sql", "-I", "audit.ignore",
                                               "-S", "-D", "DROP_TABLE"]}
                  ]
                }
                """);

        Assertions.assertEquals(
                List.of("-s", "new.sql", "-t", "old.sql", "--ignore-schema", "schemas"),
                manifest.commonArgs());
        Assertions.assertEquals(2, manifest.outputs().size());
        Assertions.assertEquals("main", manifest.outputs().get(0).name());
        Assertions.assertEquals(List.of("-o", "main.sql", "-X"),
                manifest.outputs().get(0).args());
        Assertions.assertEquals("audit", manifest.outputs().get(1).name());
    }

    @Test
    void allWhitelistedOutputOptionsAreAccepted() throws Exception {
        BatchManifest manifest = read("""
                {
                  "common": ["-s", "new.sql", "-t", "old.sql"],
                  "outputs": [
                    {"name": "all-flags", "args": [
                      "-o", "out.sql", "--out-charset", "UTF-8",
                      "-I", "a.ignore", "--ignore-list", "b.ignore",
                      "-X", "--generate-exist", "--generate-exist-do-block",
                      "--drop-before-create", "--migrate-data",
                      "-S", "-D", "DROP_TABLE", "--selected-only",
                      "--comments-to-end", "--generate-constraint-not-valid",
                      "--using-off", "--concurrently-mode", "--stop-not-allowed",
                      "-O", "TABLE", "--pre-script", "pre.sql", "--post-script", "post.sql"
                    ]}
                  ]
                }
                """);

        Assertions.assertEquals(1, manifest.outputs().size());
    }

    @Test
    void projectFileFilterIsAcceptedOnlyAsACommonArgument() throws Exception {
        BatchManifest manifest = read("""
                {
                  "common": ["-s", "new-project", "-t", "old-project",
                             "--project-file-filter", "project.filter"],
                  "outputs": [
                    {"name": "main", "args": ["-o", "out.sql"]}
                  ]
                }
                """);

        Assertions.assertEquals(List.of(
                "-s", "new-project", "-t", "old-project",
                "--project-file-filter", "project.filter"), manifest.commonArgs());
    }

    @Test
    void noCheckFunctionBodiesIsAcceptedAsACommonArgument() throws Exception {
        BatchManifest manifest = read("""
                {
                  "common": ["-s", "new-project", "-t", "old-project", "-F"],
                  "outputs": [
                    {"name": "main", "args": ["-o", "out.sql"]}
                  ]
                }
                """);

        Assertions.assertEquals(List.of(
                "-s", "new-project", "-t", "old-project", "-F"),
                manifest.commonArgs());
    }

    @Test
    void malformedJsonIsReportedWithPosition() throws IOException {
        Path manifestPath = write("{\"common\": [}");
        CmdLineException ex = Assertions.assertThrows(CmdLineException.class,
                () -> BatchManifest.read(manifestPath));

        Assertions.assertTrue(ex.getMessage().startsWith(
                        "Invalid batch manifest " + manifestPath),
                ex.getMessage());
        Assertions.assertTrue(ex.getMessage().contains("line 1 column 13"), ex.getMessage());
    }

    @Test
    void missingManifestFileIsReported() {
        Path missing = tempDir.resolve("no_such_manifest.json");
        CmdLineException ex = Assertions.assertThrows(CmdLineException.class,
                () -> BatchManifest.read(missing));

        Assertions.assertTrue(ex.getMessage().startsWith(
                "Failed to read batch manifest " + missing), ex.getMessage());
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = ';', value = {
            // structural errors
            "[];manifest root must be a JSON object with \"common\" and \"outputs\" keys",

            "{\"common\": [], \"outputs\": [{\"name\": \"a\", \"args\": []}], \"extra\": 1};"
                    + "unknown key \"extra\"",

            "{\"common\": {}, \"outputs\": [{\"name\": \"a\", \"args\": []}]};"
                    + "\"common\" must be an array of strings",

            "{\"common\": [1], \"outputs\": [{\"name\": \"a\", \"args\": []}]};"
                    + "\"common\" must be an array of strings",

            "{\"common\": [], \"outputs\": []};"
                    + "\"outputs\" must be a non-empty array",

            "{\"common\": [], \"outputs\": [\"a\"]};"
                    + "every output must be an object with \"name\" and \"args\" keys",

            "{\"common\": [], \"outputs\": [{\"name\": \" \", \"args\": []}]};"
                    + "output \"name\" must be a non-blank string",

            "{\"common\": [], \"outputs\": [{\"name\": \"a\", \"args\": [], \"out\": \"x\"}]};"
                    + "unknown key \"out\"",

            "{\"common\": [], \"outputs\": [{\"name\": \"a\", \"args\": []}, "
                    + "{\"name\": \"a\", \"args\": []}]};"
                    + "duplicate output name \"a\"",

            // argument placement errors
            "{\"common\": [\"--run-on\", \"jdbc:x\"], "
                    + "\"outputs\": [{\"name\": \"a\", \"args\": []}]};"
                    + "option --run-on is not allowed in a batch manifest",

            "{\"common\": [\"--mode\", \"DIFF\"], "
                    + "\"outputs\": [{\"name\": \"a\", \"args\": []}]};"
                    + "option --mode is not allowed in a batch manifest",

            "{\"common\": [\"-o\", \"x.sql\"], "
                    + "\"outputs\": [{\"name\": \"a\", \"args\": []}]};"
                    + "option -o is not allowed in the \"common\" section, specify it per output",

            "{\"common\": [], \"outputs\": [{\"name\": \"a\", "
                    + "\"args\": [\"--run-on-target\"]}]};"
                    + "option --run-on-target is not allowed in a batch manifest",

            "{\"common\": [], \"outputs\": [{\"name\": \"a\", "
                    + "\"args\": [\"--in-charset\", \"UTF-8\"]}]};"
                    + "option --in-charset is not allowed in batch output \"a\": "
                    + "only post-load script options may differ between outputs",

            "{\"common\": [], \"outputs\": [{\"name\": \"a\", "
                    + "\"args\": [\"--ignore-schema\", \"s\"]}]};"
                    + "option --ignore-schema is not allowed in batch output \"a\": "
                    + "only post-load script options may differ between outputs",

            "{\"common\": [], \"outputs\": [{\"name\": \"a\", "
                    + "\"args\": [\"--project-file-filter\", \"project.filter\"]}]};"
                    + "option --project-file-filter is not allowed in batch output \"a\": "
                    + "only post-load script options may differ between outputs",

            "{\"common\": [], \"outputs\": [{\"name\": \"a\", "
                    + "\"args\": [\"-F\"]}]};"
                    + "option -F is not allowed in batch output \"a\": "
                    + "only post-load script options may differ between outputs",

            "{\"common\": [], \"outputs\": [{\"name\": \"a\", "
                    + "\"args\": [\"--no-check-function-bodies\"]}]};"
                    + "option --no-check-function-bodies is not allowed in batch output \"a\": "
                    + "only post-load script options may differ between outputs",

            "{\"common\": [], \"outputs\": [{\"name\": \"a\", "
                    + "\"args\": [\"out.sql\"]}]};"
                    + "unexpected argument \"out.sql\" in batch output \"a\": "
                    + "only options are allowed",

            "{\"common\": [], \"outputs\": [{\"name\": \"a\", \"args\": [\"-I\"]}]};"
                    + "option -I in batch output \"a\" requires a value"
    })
    void invalidManifestsAreRejected(String json, String expectedMessage) throws IOException {
        Path manifestPath = write(json);
        CmdLineException ex = Assertions.assertThrows(CmdLineException.class,
                () -> BatchManifest.read(manifestPath));

        String message = ex.getMessage();
        String expected = expectedMessage.startsWith("option ")
                || expectedMessage.startsWith("unexpected argument ")
                ? expectedMessage
                : "Invalid batch manifest %s: %s".formatted(manifestPath, expectedMessage);
        Assertions.assertEquals(expected, message);
    }

    private BatchManifest read(String json) throws IOException, CmdLineException {
        return BatchManifest.read(write(json));
    }

    private Path write(String json) throws IOException {
        Path manifestPath = Files.createTempFile(tempDir, "batch", ".json");
        Files.writeString(manifestPath, json);
        return manifestPath;
    }
}
