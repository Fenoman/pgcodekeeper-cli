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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parsed and validated batch mode manifest.
 * <p>
 * The manifest is a JSON object with two keys: {@code common} (an array of
 * CLI argument strings shared by every output, including the comparison
 * sources and all load options) and {@code outputs} (a non-empty array of
 * {@code {"name": ..., "args": [...]}} objects). Each output's effective
 * arguments are {@code common + args}; a standalone DIFF invocation with
 * exactly those arguments produces a byte-identical script.
 * <p>
 * Output {@code args} may contain only options applied after loading
 * (ignore lists, script generation flags, the output file), so that one
 * loaded comparison can serve every output.
 */
record BatchManifest(List<String> commonArgs, List<BatchManifest.Output> outputs) {

    record Output(String name, List<String> args) {
    }

    private static final String COMMON_KEY = "common"; //$NON-NLS-1$
    private static final String OUTPUTS_KEY = "outputs"; //$NON-NLS-1$
    private static final String NAME_KEY = "name"; //$NON-NLS-1$
    private static final String ARGS_KEY = "args"; //$NON-NLS-1$

    /**
     * Options that make no sense inside a batch manifest: mode selection,
     * informational commands and script application on live databases.
     */
    private static final Set<String> FORBIDDEN_OPTIONS = Set.of(
            "--mode", "--batch-manifest", //$NON-NLS-1$ //$NON-NLS-2$
            "--run-on", "-R", "--run-on-target", "-r", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "--help", "--version", "--list-charsets", "--clear-lib-cache"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * Options verified to act strictly after both sides are loaded: they are
     * consumed by the diff tree flattener, the script builder or the CLI
     * output/danger-check step, never by the loaders. Only these may differ
     * between outputs of one batch run.
     */
    private static final Set<String> OUTPUT_OPTIONS = Set.of(
            "--output", "-o", //$NON-NLS-1$ //$NON-NLS-2$
            "--out-charset", //$NON-NLS-1$
            "--ignore-list", "-I", //$NON-NLS-1$ //$NON-NLS-2$
            "--add-transaction", "-X", //$NON-NLS-1$ //$NON-NLS-2$
            "--generate-exist", //$NON-NLS-1$
            "--generate-exist-do-block", "-do", //$NON-NLS-1$ //$NON-NLS-2$
            "--drop-before-create", //$NON-NLS-1$
            "--migrate-data", //$NON-NLS-1$
            "--safe-mode", "-S", //$NON-NLS-1$ //$NON-NLS-2$
            "--allow-danger-ddl", "-D", //$NON-NLS-1$ //$NON-NLS-2$
            "--selected-only", //$NON-NLS-1$
            "--comments-to-end", //$NON-NLS-1$
            "--generate-constraint-not-valid", "-v", //$NON-NLS-1$ //$NON-NLS-2$
            "--using-off", //$NON-NLS-1$
            "--concurrently-mode", "-C", //$NON-NLS-1$ //$NON-NLS-2$
            "--stop-not-allowed", //$NON-NLS-1$
            "--allowed-object", "-O", //$NON-NLS-1$ //$NON-NLS-2$
            "--pre-script", //$NON-NLS-1$
            "--post-script"); //$NON-NLS-1$

    /**
     * Whitelisted output options that consume the following token as a value.
     */
    private static final Set<String> OUTPUT_OPTIONS_WITH_VALUE = Set.of(
            "--output", "-o", //$NON-NLS-1$ //$NON-NLS-2$
            "--out-charset", //$NON-NLS-1$
            "--ignore-list", "-I", //$NON-NLS-1$ //$NON-NLS-2$
            "--allow-danger-ddl", "-D", //$NON-NLS-1$ //$NON-NLS-2$
            "--allowed-object", "-O", //$NON-NLS-1$ //$NON-NLS-2$
            "--pre-script", //$NON-NLS-1$
            "--post-script"); //$NON-NLS-1$

    /**
     * Reads and validates a batch manifest file.
     *
     * @param manifestPath path to the JSON manifest
     * @return the validated manifest
     * @throws CmdLineException if the file cannot be read or its content is invalid
     */
    static BatchManifest read(Path manifestPath) throws CmdLineException {
        String text;
        try {
            text = Files.readString(manifestPath, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw invalid(Messages.Batch_error_manifest_read
                    .formatted(manifestPath, ex.getMessage()));
        }

        Object root;
        try {
            root = JsonParser.parse(text);
        } catch (JsonParser.JsonException ex) {
            throw invalid(Messages.Batch_error_manifest_invalid
                    .formatted(manifestPath, ex.getMessage()));
        }

        BatchManifest manifest = extract(manifestPath, root);
        manifest.validateArguments();
        return manifest;
    }

    private static BatchManifest extract(Path manifestPath, Object root)
            throws CmdLineException {
        Map<?, ?> rootObject = asObject(manifestPath, root);
        for (Object key : rootObject.keySet()) {
            if (!COMMON_KEY.equals(key) && !OUTPUTS_KEY.equals(key)) {
                throw invalidManifest(manifestPath,
                        Messages.Batch_error_unknown_key.formatted(key));
            }
        }

        List<String> commonArgs = asStringList(manifestPath,
                rootObject.get(COMMON_KEY), COMMON_KEY);
        List<Output> outputs = extractOutputs(manifestPath, rootObject.get(OUTPUTS_KEY));
        return new BatchManifest(List.copyOf(commonArgs), List.copyOf(outputs));
    }

    private static Map<?, ?> asObject(Path manifestPath, Object root)
            throws CmdLineException {
        if (root instanceof Map<?, ?> rootObject) {
            return rootObject;
        }
        throw invalidManifest(manifestPath, Messages.Batch_error_root_object);
    }

    private static List<Output> extractOutputs(Path manifestPath, Object outputsValue)
            throws CmdLineException {
        if (!(outputsValue instanceof List<?> entries) || entries.isEmpty()) {
            throw invalidManifest(manifestPath, Messages.Batch_error_outputs_empty);
        }

        List<Output> outputs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object entry : entries) {
            Output output = extractOutput(manifestPath, entry);
            if (!names.add(output.name())) {
                throw invalidManifest(manifestPath,
                        Messages.Batch_error_duplicate_output_name.formatted(output.name()));
            }
            outputs.add(output);
        }
        return outputs;
    }

    private static Output extractOutput(Path manifestPath, Object entry)
            throws CmdLineException {
        if (!(entry instanceof Map<?, ?> entryObject)) {
            throw invalidManifest(manifestPath, Messages.Batch_error_output_entry);
        }
        for (Object key : entryObject.keySet()) {
            if (!NAME_KEY.equals(key) && !ARGS_KEY.equals(key)) {
                throw invalidManifest(manifestPath,
                        Messages.Batch_error_unknown_key.formatted(key));
            }
        }
        if (!(entryObject.get(NAME_KEY) instanceof String name) || name.isBlank()) {
            throw invalidManifest(manifestPath, Messages.Batch_error_output_name_blank);
        }
        List<String> args = asStringList(manifestPath, entryObject.get(ARGS_KEY), ARGS_KEY);
        return new Output(name, List.copyOf(args));
    }

    private static List<String> asStringList(Path manifestPath, Object value, String key)
            throws CmdLineException {
        if (!(value instanceof List<?> list)) {
            throw invalidManifest(manifestPath,
                    Messages.Batch_error_args_not_strings.formatted(key));
        }
        List<String> strings = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof String string)) {
                throw invalidManifest(manifestPath,
                        Messages.Batch_error_args_not_strings.formatted(key));
            }
            strings.add(string);
        }
        return strings;
    }

    private void validateArguments() throws CmdLineException {
        for (String token : commonArgs) {
            String optionName = optionName(token);
            if (optionName == null) {
                continue;
            }
            if (FORBIDDEN_OPTIONS.contains(optionName)) {
                throw invalid(Messages.Batch_error_option_forbidden.formatted(optionName));
            }
            if ("--output".equals(optionName) || "-o".equals(optionName)) { //$NON-NLS-1$ //$NON-NLS-2$
                throw invalid(Messages.Batch_error_option_not_common.formatted(optionName));
            }
        }

        for (Output output : outputs) {
            validateOutputArguments(output);
        }
    }

    private static void validateOutputArguments(Output output) throws CmdLineException {
        List<String> args = output.args();
        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            String optionName = optionName(token);
            if (optionName == null) {
                throw invalid(Messages.Batch_error_positional_in_output
                        .formatted(token, output.name()));
            }
            if (FORBIDDEN_OPTIONS.contains(optionName)) {
                throw invalid(Messages.Batch_error_option_forbidden.formatted(optionName));
            }
            if (!OUTPUT_OPTIONS.contains(optionName)) {
                throw invalid(Messages.Batch_error_option_not_output
                        .formatted(optionName, output.name()));
            }
            if (OUTPUT_OPTIONS_WITH_VALUE.contains(optionName) && !token.contains("=")) { //$NON-NLS-1$
                i++;
                if (i >= args.size()) {
                    throw invalid(Messages.Batch_error_option_needs_value
                            .formatted(optionName, output.name()));
                }
            }
        }
    }

    private static String optionName(String token) {
        if (token.length() < 2 || token.charAt(0) != '-') {
            return null;
        }
        int equalsIndex = token.indexOf('=');
        return equalsIndex < 0 ? token : token.substring(0, equalsIndex);
    }

    private static CmdLineException invalidManifest(Path manifestPath, String detail) {
        return invalid(Messages.Batch_error_manifest_invalid.formatted(manifestPath, detail));
    }

    private static CmdLineException invalid(String message) {
        return new CmdLineException(null, message, null);
    }
}
