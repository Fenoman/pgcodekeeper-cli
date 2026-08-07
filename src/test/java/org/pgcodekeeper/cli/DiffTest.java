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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

class DiffTest {

    private static Stream<Arguments> generator() {
        return Stream.<Supplier<ArgumentsProvider>>of(
                SourceTargerArgumentsProvider::new,
                AddTestArgumentsProvider::new,
                ModifyTestArgumentsProvider::new,
                DangerTableArgumentsProvider::new,
                DangerDropColArgumentsProvider::new,
                DangerAlterColArgumentsProvider::new,
                FlagsArgumentsProvider::new,
                IgnoreListsArgumentsProvider::new,
                AllowedObjectsArgumentsProvider::new,
                AllowedObjectsChangeTrackingArgumentsProvider::new,
                AllowedObjectsSysVerArgumentsProvider::new,
                LibrariesArgumentsProvider::new,
                LibrariesNoPrivArgumentsProvider::new,
                LibrariesXmlArgumentsProvider::new,
                SelectedOnlyArgumentsProvider::new,
                AddConstraintNotValid::new,
                AddDropBeforeCreate::new,
                GenerateExistDoBlock::new)
                .flatMap(factory -> Stream.of(false, true)
                        .map(parallel -> Arguments.of(factory.get(), parallel)));
    }

    @ParameterizedTest
    @MethodSource("generator")
    void mainTest(ArgumentsProvider args, boolean parallel)
            throws IOException, URISyntaxException {
        try (args) {
            boolean result = Application.process(withParallelLoad(args.args(), parallel));
            Path resFile = args.getDiffResultFile();
            Path predefined = args.getPredefinedResultFile();
            String name = args.getClass().getSimpleName() + ", parallel=" + parallel;

            Assertions.assertTrue(result, name + " - Diff finished with error");
            Assertions.assertTrue(Files.exists(predefined), name + " - Predefined file does not exist: " + predefined);
            Assertions.assertTrue(Files.exists(resFile), name + " - Resulting file does not exist: " + resFile);

            Assertions.assertFalse(Files.isDirectory(predefined),
                    name + " - Predefined file is a directory: " + predefined);
            Assertions.assertFalse(Files.isDirectory(resFile), name + " - Resulting file is a directory: " + resFile);
            if (!filesEqualIgnoreNewLines(predefined, resFile)) {
                Assertions.assertEquals(
                        Files.readString(predefined),
                        args.getDiffFileContents(),
                        name + " - Predefined and resulting script differ");
            }
        }
    }

    @Test
    void projectFileFilterIsSharedByBothSidesAndParallelModesAreByteIdentical(
            @TempDir Path tempDir) throws IOException {
        Path oldProject = tempDir.resolve("old-project");
        Path newProject = tempDir.resolve("new-project");
        writeProjectFile(oldProject, "SCHEMA/public/public.sql", "CREATE SCHEMA public;");
        writeProjectFile(newProject, "SCHEMA/public/public.sql", "CREATE SCHEMA public;");

        writeProjectFile(oldProject, "SCHEMA/public/TABLE/kept_table.sql",
                "CREATE TABLE public.kept_table (id integer);");
        writeProjectFile(newProject, "SCHEMA/public/TABLE/kept_table.sql",
                "CREATE TABLE public.kept_table (id bigint);");
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

        Path filter = Files.writeString(tempDir.resolve("project.filter"), """
                EXCLUDE REGEX ^SCHEMA/public/TABLE/.*\\.sql$
                INCLUDE PATH SCHEMA/public/TABLE/kept_table.sql
                INCLUDE PATH SCHEMA/public/TABLE/included_exception.sql
                """);
        Path sequential = tempDir.resolve("sequential.sql");
        Path parallel = tempDir.resolve("parallel.sql");

        Assertions.assertTrue(Application.process(new String[] {
                "--no-parallel-load", "--project-file-filter", filter.toString(),
                "-s", newProject.toString(), "-t", oldProject.toString(),
                "-o", sequential.toString()
        }));
        Assertions.assertTrue(Application.process(new String[] {
                "--parallel-load", "--project-file-filter", filter.toString(),
                "-s", newProject.toString(), "-t", oldProject.toString(),
                "-o", parallel.toString()
        }));

        Assertions.assertArrayEquals(Files.readAllBytes(sequential),
                Files.readAllBytes(parallel),
                "sequential and parallel filtered output must be byte-identical");
        String script = Files.readString(sequential);
        Assertions.assertAll(
                () -> Assertions.assertTrue(script.contains("kept_table"), script),
                () -> Assertions.assertTrue(script.contains("included_exception"), script),
                () -> Assertions.assertFalse(script.contains("unwanted"), script),
                () -> Assertions.assertFalse(script.contains("broken"), script));
    }

    private static String[] withParallelLoad(String[] arguments, boolean parallel) {
        // Select each loading path explicitly and require byte-identical output.
        String[] result = new String[arguments.length + 1];
        result[0] = parallel ? "--parallel-load" : "--no-parallel-load";
        System.arraycopy(arguments, 0, result, 1, arguments.length);
        return result;
    }

    private static void writeProjectFile(Path projectRoot, String relativePath, String sql)
            throws IOException {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, sql + '\n');
    }

    private boolean filesEqualIgnoreNewLines(Path f1, Path f2) throws IOException {
        try (BufferedReader reader1 = Files.newBufferedReader(f1, StandardCharsets.UTF_8);
             BufferedReader reader2 = Files.newBufferedReader(f2, StandardCharsets.UTF_8);) {

            while (true) {
                String line1 = getNextLine(reader1);
                String line2 = getNextLine(reader2);
                if (!Objects.equals(line1, line2)) {
                    return false;
                }

                if (line1 == null) {
                    return true;
                }
            }
        }
    }

    /**
     * Iterates through <code>reader</code> line by line until reaches not empty line or EOF
     *
     * @return next not empty line or null if EOF is reached
     */
    private String getNextLine(BufferedReader reader) throws IOException {
        String nextLine;

        while ((nextLine = reader.readLine()) != null && nextLine.isEmpty()) {
            // skip to next line
        }

        return nextLine;
    }
}

/**
 * {@link ArgumentsProvider} implementation testing src + target mode
 */
class SourceTargerArgumentsProvider extends ArgumentsProvider {

    protected SourceTargerArgumentsProvider() {
        super("drop_ms_table");
    }

    @Override
    public String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);

        return new String[]{"-S", "--db-type", "MS", "-D", "DROP_TABLE", "-o", getDiffResultFile().toString(),
                "-t", fOriginal.toString(), "-s", fNew.toString()};
    }
}

/**
 * {@link ArgumentsProvider} implementation testing diff
 */
class AddTestArgumentsProvider extends ArgumentsProvider {

    protected AddTestArgumentsProvider() {
        super("add_cluster");
    }

    @Override
    public String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);

        return new String[]{"-o", getDiffResultFile().toString(),
                "-t", fOriginal.toString(), "-s", fNew.toString()};
    }
}

/**
 * {@link ArgumentsProvider} implementation testing diff
 */
class ModifyTestArgumentsProvider extends ArgumentsProvider {

    protected ModifyTestArgumentsProvider() {
        super("modify_function_args2");
    }

    @Override
    public String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);

        return new String[]{"-o", getDiffResultFile().toString(),
                fNew.toString(), fOriginal.toString()};
    }
}

/**
 * {@link ArgumentsProvider} implementation testing successful dangerous statements
 */
class DangerTableArgumentsProvider extends ArgumentsProvider {

    public DangerTableArgumentsProvider() {
        super("drop_ms_table");
    }

    @Override
    public String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);

        return new String[]{"--safe-mode", "--db-type", "MS", "--allow-danger-ddl", "DROP_TABLE",
                "-o", getDiffResultFile().toString(),
                fNew.toString(), fOriginal.toString()};
    }
}

/**
 * {@link ArgumentsProvider} implementation testing successful dangerous statements
 */
class DangerDropColArgumentsProvider extends ArgumentsProvider {

    public DangerDropColArgumentsProvider() {
        super("drop_column");
    }

    @Override
    public String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);

        return new String[]{"-S", "-D", "DROP_COLUMN",
                "-o", getDiffResultFile().toString(),
                fNew.toString(), fOriginal.toString()};
    }
}


/**
 * {@link ArgumentsProvider} implementation testing successful dangerous statements
 */
class DangerAlterColArgumentsProvider extends ArgumentsProvider {

    public DangerAlterColArgumentsProvider() {
        super("modify_column_type");
    }

    @Override
    public String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);

        return new String[]{"--safe-mode", "--allow-danger-ddl",
                "ALTER_COLUMN", "-o", getDiffResultFile().toString(),
                fNew.toString(), fOriginal.toString()};
    }
}

/**
 * {@link ArgumentsProvider} implementation testing all other flags
 */
class FlagsArgumentsProvider extends ArgumentsProvider {

    public FlagsArgumentsProvider() {
        super("modify_column_type");
    }

    @Override
    public String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);

        return new String[]{"--safe-mode", "-X", "-F", "-Z", "UTC",
                "-D", "ALTER_COLUMN", "--output", getDiffResultFile().toString(),
                fNew.toString(), fOriginal.toString()};
    }

    @Override
    public Path getPredefinedResultFile() throws URISyntaxException, IOException {
        return TestUtils.getPathToResource(DiffTest.class, "MainTest_" + resName + FILES_POSTFIX.DIFF_SQL);
    }
}

/**
 * {@link ArgumentsProvider} implementation for IgnoreList test
 */
class IgnoreListsArgumentsProvider extends ArgumentsProvider {

    public IgnoreListsArgumentsProvider() {
        super("ignore");
    }

    @Override
    protected String[] args() throws URISyntaxException, IOException {
        Path black = TestUtils.getPathToResource(DiffTest.class, "black.ignore");
        Path white = TestUtils.getPathToResource(DiffTest.class, "white.ignore");
        String oldPath = TestUtils.getPathToResource(DiffTest.class, "ignore_old.sql").toString();
        String newPath = TestUtils.getPathToResource(DiffTest.class, "ignore_new.sql").toString();

        return new String[]{"--ignore-list", black.toString(), "-I", white.toString(), "-o",
                getDiffResultFile().toString(), newPath, oldPath};
    }
}

/**
 * {@link ArgumentsProvider} implementation for AllowedObjects test
 */
class AllowedObjectsArgumentsProvider extends ArgumentsProvider {

    public AllowedObjectsArgumentsProvider() {
        super("same_allowed_object");
    }

    @Override
    protected String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);
        return new String[]{"--allowed-object", "FUNCTION", "--allowed-object", "VIEW",
                "-O", "INDEX", "-o", getDiffResultFile().toString(),
                fNew.toString(), fOriginal.toString()};
    }
}

/**
 * {@link ArgumentsProvider} implementation for libraries test with --tgt-lib
 */
class LibrariesArgumentsProvider extends ArgumentsProvider {

    public LibrariesArgumentsProvider() {
        super("libraries");
    }

    @Override
    protected String[] args() throws URISyntaxException, IOException {
        Path projectDir = exportToProject();
        Path lib = TestUtils.getPathToResource(DiffTest.class, "lib.sql");
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);

        return new String[]{"-o", getDiffResultFile().toString(),
                "-t", projectDir.toString(), "--tgt-lib", lib.toString(),
                "-s", fNew.toString()};
    }

    protected Path exportToProject() throws URISyntaxException, IOException {
        Path projectDir = getParseResultDir().get();
        Path dumpFile = getFile(FILES_POSTFIX.ORIGINAL_SQL);
        Application.process(new String[]{
                "--mode", "parse", "-o", projectDir.toString(), dumpFile.toString()
        });
        return projectDir;
    }
}

/**
 * {@link ArgumentsProvider} implementation for libraries test with --tgt-lib-no-priv
 */
class LibrariesNoPrivArgumentsProvider extends LibrariesArgumentsProvider {

    @Override
    protected String[] args() throws URISyntaxException, IOException {
        Path projectDir = exportToProject();
        Path lib = TestUtils.getPathToResource(DiffTest.class, "lib.sql");
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);

        return new String[]{"-o", getDiffResultFile().toString(),
                "-t", projectDir.toString(), "--tgt-lib-no-priv", lib.toString(),
                "-s", fNew.toString()};
    }

    @Override
    public Path getPredefinedResultFile() throws URISyntaxException, IOException {
        return getFile(FILES_POSTFIX.DIFF_SQL, "libraries_no_priv");
    }
}

/**
 * {@link ArgumentsProvider} implementation for libraries test with --tgt-lib-xml
 */
class LibrariesXmlArgumentsProvider extends LibrariesArgumentsProvider {

    @Override
    protected String[] args() throws URISyntaxException, IOException {
        Path projectDir = exportToProject();
        Path lib = TestUtils.getPathToResource(DiffTest.class, "lib.sql");
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);

        Path xmlFile = projectDir.resolve("lib_dependencies.xml");
        Files.writeString(xmlFile, """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <dependencies loadNested="false">
                    <dependency ignorePriv="false" owner="" path="%s"/>
                </dependencies>
                """.formatted(lib.toString()));

        return new String[]{"-o", getDiffResultFile().toString(),
                "-t", projectDir.toString(), "--tgt-lib-xml", xmlFile.toString(),
                "-s", fNew.toString()};
    }
}

/**
 * {@link ArgumentsProvider} implementation for test the use in script only
 * objects with difference
 */
class SelectedOnlyArgumentsProvider extends ArgumentsProvider {

    public SelectedOnlyArgumentsProvider() {
        super("selected_only");
    }

    @Override
    protected String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);
        return new String[]{"--selected-only", "-o",
                getDiffResultFile().toString(),
                fNew.toString(), fOriginal.toString()};
    }
}

/**
 * {@link ArgumentsProvider} implementation for test the use in script only
 * objects with difference
 */
class AllowedObjectsChangeTrackingArgumentsProvider extends ArgumentsProvider {

    public AllowedObjectsChangeTrackingArgumentsProvider() {
        super("alter_ms_pk_constraint_in_table_with_change_tracking");
    }

    @Override
    protected String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);
        return new String[]{"--db-type", "MS", "-O", "CONSTRAINT", "-o", getDiffResultFile().toString(),
                fNew.toString(), fOriginal.toString()};
    }
}

/**
 * {@link ArgumentsProvider} implementation for test the use in script only
 * objects with difference
 */
class AllowedObjectsSysVerArgumentsProvider extends ArgumentsProvider {

    public AllowedObjectsSysVerArgumentsProvider() {
        super("alter_ms_pk_constraint_in_sys_ver_table");
    }

    @Override
    protected String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);
        return new String[]{"--db-type", "MS", "-O", "CONSTRAINT", "-o", getDiffResultFile().toString(),
                fNew.toString(), fOriginal.toString()};
    }
}

/**
 * {@link ArgumentsProvider} implementation for generate CONSTRAINT NOT VALID
 * test
 */
class AddConstraintNotValid extends ArgumentsProvider {

    public AddConstraintNotValid() {
        super("generate_constraint_not_valid");
    }

    @Override
    protected String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);

        return new String[]{"-v", "-o", getDiffResultFile().toString(),
                fNew.toString(), fOriginal.toString()};
    }
}

/**
 * {@link ArgumentsProvider} implementation for generate DROP before CREATE test
 */
class AddDropBeforeCreate extends ArgumentsProvider {

    public AddDropBeforeCreate() {
        super("drop_before_create");
    }

    @Override
    protected String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);

        return new String[]{"--drop-before-create", "-o", getDiffResultFile().toString(),
                fNew.toString(), fOriginal.toString()};
    }
}

/**
 * {@link ArgumentsProvider} implementation for print checking existence sequence, constraints
 */
class GenerateExistDoBlock extends ArgumentsProvider {

    public GenerateExistDoBlock() {
        super("generate_exist_do_block");
    }

    @Override
    protected String[] args() throws URISyntaxException, IOException {
        Path fNew = getFile(FILES_POSTFIX.NEW_SQL);
        Path fOriginal = getFile(FILES_POSTFIX.ORIGINAL_SQL);

        return new String[]{"-do", "-o", getDiffResultFile().toString(),
                fNew.toString(), fOriginal.toString()};
    }
}
