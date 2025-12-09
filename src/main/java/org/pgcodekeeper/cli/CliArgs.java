/*******************************************************************************
 * Copyright 2017-2025 TAXTELECOM, LLC
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

import org.kohsuke.args4j.*;
import org.pgcodekeeper.cli.localizations.CliArgsLocalizationsBundle;
import org.pgcodekeeper.cli.localizations.Messages;
import org.pgcodekeeper.cli.opthandlers.BooleanNoDefOptionHandler;
import org.pgcodekeeper.cli.opthandlers.DangerStatementOptionHandler;
import org.pgcodekeeper.cli.opthandlers.DbObjTypeOptionHandler;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.DatabaseType;
import org.pgcodekeeper.core.database.base.IDatabaseProvider;
import org.pgcodekeeper.core.database.ch.ChDatabaseProvider;
import org.pgcodekeeper.core.database.ms.MsDatabaseProvider;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.formatter.FormatConfiguration;
import org.pgcodekeeper.core.model.difftree.DbObjType;
import org.pgcodekeeper.core.settings.ISettings;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Extension of {@link ISettings} with annotated CLI fields.
 * Override getters so that clients will access either CLI or parent fields
 * depending on the instance.
 */
public class CliArgs implements ISettings {

    enum CliMode {
        DIFF,
        PARSE,
        GRAPH
    }

    private static final String URL_START_JDBC = "jdbc:"; //$NON-NLS-1$
    private static final int DEFAULT_DEPTH = 10;

    private IDatabaseProvider provider;

    // SONAR-OFF
    {
        // this was moved to initializer to avoid the IDE making the field "final" on-save
        // otherwise args4j breaks
        this.allowedDangers = new ArrayList<>();
        this.allowedTypes = new ArrayList<>();
        this.graphFilterTypes = new ArrayList<>();
        this.ignoreLists = new ArrayList<>();
        this.sourceLibXmls = new ArrayList<>();
        this.sourceLibs = new ArrayList<>();
        this.sourceLibsWithoutPriv = new ArrayList<>();
        this.targetLibXmls = new ArrayList<>();
        this.targetLibs = new ArrayList<>();
        this.targetLibsWithoutPriv = new ArrayList<>();
        this.preFilePath = new ArrayList<>();
        this.postFilePath = new ArrayList<>();
        this.graphNames = new ArrayList<>();
        this.inCharsetName = Consts.UTF_8;
        this.outCharsetName = Consts.UTF_8;
        this.graphDepth = DEFAULT_DEPTH;
        this.dbType = DatabaseType.PG;
        this.mode = CliMode.DIFF;
    }
    // SONAR-ON

    // "special" options start with "z"

    // all (external) source <-> target (internal) swaps should be done here
    // and not in the (internal) program logic to avoid confusion and accidents
    // only here, everything "source" refers to the NEW DB, and target to OLD DB

    @Option(name="--help", help=true, usage="Help")
    private boolean zhelp;

    @Option(name="--version", help=true, usage="Version")
    private boolean zversion;

    @Option(name="--list-charsets", help=true, usage="ListCharsets")
    private boolean zlistCharsets;

    @Option(name="--clear-lib-cache", help=true, usage="ClearLibCache")
    private boolean clearLibCache;

    @Option(name="--mode", usage="mode")
    private CliMode mode;

    @Option(name="-s", depends="-t", aliases="--source", metaVar= CliArgsLocalizationsBundle.PATH_OR_JDBC,
            usage="source")
    @Argument(index=0, metaVar=CliArgsLocalizationsBundle.SOURCE, usage="source")
    private String newSrc;

    @Option(name="-t", depends="-s", aliases="--target", metaVar=CliArgsLocalizationsBundle.PATH_OR_JDBC, usage="target")
    @Argument(index=1, metaVar=CliArgsLocalizationsBundle.DEST, usage="target")
    private String oldSrc;

    @Option(name="-o", aliases="--output", metaVar=CliArgsLocalizationsBundle.PATH, usage="output")
    private String outputTarget;

    @Option(name="-r", aliases="--run-on-target", forbids="-R",
            usage="run-on-target")
    private boolean runOnTarget;

    @Option(name="-R", aliases="--run-on", metaVar=CliArgsLocalizationsBundle.JDBC, forbids="-r", usage="run-on")
    private String runOnDb;

    @Option(name="--in-charset", metaVar=CliArgsLocalizationsBundle.CHARSET, usage="in-charset")
    private String inCharsetName;

    @Option(name="--out-charset", metaVar=CliArgsLocalizationsBundle.CHARSET, usage="out-charset")
    private String outCharsetName;

    @Option(name="-E", aliases="--error", usage = "error")
    private boolean isDebug;

    @Option(name="--ignore-errors", usage="ignore-errors")
    private boolean ignoreErrors;

    @Option(name="-P", aliases="--no-privileges", usage="no-privileges")
    private boolean ignorePrivileges;

    @Option(name="-L", aliases="--keep-newlines", usage="keep-newlines")
    private boolean keepNewlines;

    @Option(name="--simplify-views", usage="simplify-views")
    private boolean simplifyView;

    @Option(name="-X", aliases="--add-transaction", usage="add-transaction")
    private boolean addTransaction;

    @Option(name="-F", aliases="--no-check-function-bodies", usage="no-check-function-bodies")
    private boolean disableCheckFunctionBodies;

    @Option(name="-f", aliases="--enable-function-bodies-dependencies", usage="enable-function-bodies-dependencies")
    private boolean enableFunctionBodiesDependencies;

    @Option(name="-Z", aliases="--time-zone", metaVar=CliArgsLocalizationsBundle.TIMEZONE, usage="time-zone")
    private String timeZone;

    @Option(name="--pre-script", metaVar=CliArgsLocalizationsBundle.PATH, usage="pre-script")
    private List<String> preFilePath;

    @Option(name="--post-script", metaVar=CliArgsLocalizationsBundle.PATH, usage="post-script")
    private List<String> postFilePath;

    @Option(name="--ignore-column-order", usage="ignore-column-order")
    private boolean ignoreColumnOrder;

    @Option(name="-v", aliases="--generate-constraint-not-valid", usage="generate-constraint-not-valid")
    private boolean generateConstraintNotValid;

    @Option(name="--using-off", usage="using-off")
    private boolean usingTypeCastOff;

    @Option(name="--migrate-data", usage="migrate-data")
    private boolean dataMovementMode;

    @Option(name="-C", aliases="--concurrently-mode", usage="concurrently-mode")
    private boolean concurrentlyMode;

    @Option(name="--generate-exist", usage="generate-exist")
    private boolean generateExists;

    @Option(name="-do", aliases="--generate-exist-do-block", usage="generate-exist-do-block")
    private boolean generateExistDoBlock;

    @Option(name="--drop-before-create", usage="drop-before-create")
    private boolean dropBeforeCreate;

    @Option(name="--comments-to-end", usage="comments-to-end")
    private boolean commentsToEnd;

    @Option(name="-S", aliases="--safe-mode", usage="safe-mode")
    private boolean safeMode;

    @Option(name="-D", aliases="--allow-danger-ddl", handler= DangerStatementOptionHandler.class, usage="allow-danger-ddl")
    private List<DangerStatement> allowedDangers;

    @Option(name="-O", aliases="--allowed-object", handler= DbObjTypeOptionHandler.class, usage="allowed-object")
    private List<DbObjType> allowedTypes;

    @Option(name="--stop-not-allowed", usage = "stop-not-allowed")
    private boolean stopNotAllowed;

    @Option(name="--selected-only", usage="selected-only")
    private boolean selectedOnly;

    @Option(name="-I", aliases="--ignore-list", metaVar=CliArgsLocalizationsBundle.PATH, usage="ignore-list")
    private List<String> ignoreLists;

    @Option(name="--ignore-schema", metaVar=CliArgsLocalizationsBundle.PATH, usage="ignore-schema")
    private String ignoreSchemaList;

    @Option(name="--src-lib-xml", metaVar=CliArgsLocalizationsBundle.PATH, usage="src-lib-xml")
    private List<String> targetLibXmls;

    @Option(name="--src-lib", metaVar=CliArgsLocalizationsBundle.PATH_OR_JDBC, usage="src-lib")
    private List<String> targetLibs;

    @Option(name="--src-lib-no-priv", metaVar=CliArgsLocalizationsBundle.PATH_OR_JDBC, usage="src-lib-no-priv")
    private List<String> targetLibsWithoutPriv;

    @Option(name="--tgt-lib-xml", metaVar=CliArgsLocalizationsBundle.PATH, usage="tgt-lib-xml")
    private List<String> sourceLibXmls;

    @Option(name="--tgt-lib", metaVar=CliArgsLocalizationsBundle.PATH_OR_JDBC, usage="tgt-lib")
    private List<String> sourceLibs;

    @Option(name="--tgt-lib-no-priv", metaVar=CliArgsLocalizationsBundle.PATH_OR_JDBC, usage="tgt-lib-no-priv")
    private List<String> sourceLibsWithoutPriv;

    @Option(name="--lib-safe-mode", usage="lib-safe-mode")
    private boolean libSafeMode;

    @Option(name="-m", aliases="--ignore-concurrent-modification", usage="ignore-concurrent-modification")
    private boolean ignoreConcurrentModification;

    @Option(name="--db-type", usage="db-type")
    private DatabaseType dbType;

    @Option(name="--update-project", usage="update-project")
    private boolean projUpdate;

    @Option(name="--graph-depth", metaVar=CliArgsLocalizationsBundle.N, usage="graph-depth")
    private int graphDepth;

    @Option(name="--graph-reverse", depends="--graph-name", usage="graph-reverse")
    private boolean graphReverse;

    @Option(name="--graph-name", metaVar=CliArgsLocalizationsBundle.NAME, usage="graph-name")
    private List<String> graphNames;

    @Option(name="--graph-filter-object", handler=DbObjTypeOptionHandler.class, usage="graph-filter-object")
    private List<DbObjType> graphFilterTypes;

    @Option(name="--graph-invert-filter", depends="--graph-filter-object", usage="graph-invert-filter")
    private boolean graphInvertFilter;

    CliMode getMode() {
        return mode;
    }

    public boolean isClearLibCache() {
        return clearLibCache;
    }

    public String getNewSrc() {
        return newSrc;
    }

    public String getOldSrc() {
        return oldSrc;
    }

    public String getOutputTarget() {
        return outputTarget;
    }

    @Override
    public boolean isAddTransaction() {
        return addTransaction;
    }

    @Override
    public boolean isStopNotAllowed() {
        return stopNotAllowed;
    }

    public boolean isSafeMode() {
        return safeMode;
    }

    public boolean isRunOnTarget() {
        return runOnTarget;
    }

    public String getRunOnDb() {
        return runOnDb;
    }

    public Collection<DangerStatement> getAllowedDangers() {
        return Collections.unmodifiableCollection(allowedDangers);
    }

    public Collection<String> getIgnoreLists() {
        return Collections.unmodifiableCollection(ignoreLists);
    }

    public String getIgnoreSchemaList() {
        return ignoreSchemaList;
    }

    public Collection<String> getSourceLibXmls() {
        return Collections.unmodifiableCollection(sourceLibXmls);
    }

    public Collection<String> getSourceLibs() {
        return Collections.unmodifiableCollection(sourceLibs);
    }

    public Collection<String> getSourceLibsWithoutPriv() {
        return Collections.unmodifiableCollection(sourceLibsWithoutPriv);
    }

    public Collection<String> getTargetLibXmls() {
        return Collections.unmodifiableCollection(targetLibXmls);
    }

    public Collection<String> getTargetLibs() {
        return Collections.unmodifiableCollection(targetLibs);
    }

    public Collection<String> getTargetLibsWithoutPriv() {
        return Collections.unmodifiableCollection(targetLibsWithoutPriv);
    }

    public boolean isLibSafeMode() {
        return libSafeMode;
    }

    @Override
    public DatabaseType getDbType() {
        return dbType;
    }

    @Override
    public boolean isIgnoreConcurrentModification() {
        return ignoreConcurrentModification;
    }

    public boolean isDebug() {
        return isDebug;
    }

    public boolean isIgnoreErrors() {
        return ignoreErrors;
    }

    @Override
    public boolean isIgnoreColumnOrder() {
        return ignoreColumnOrder;
    }

    @Override
    public boolean isGenerateConstraintNotValid() {
        return generateConstraintNotValid;
    }

    @Override
    public String getInCharsetName() {
        return inCharsetName;
    }

    public String getOutCharsetName() {
        return outCharsetName;
    }

    @Override
    public boolean isDisableCheckFunctionBodies() {
        return disableCheckFunctionBodies;
    }

    @Override
    public boolean isEnableFunctionBodiesDependencies() {
        return enableFunctionBodiesDependencies;
    }

    @Override
    public String getTimeZone() {
        return timeZone;
    }

    @Override
    public boolean isIgnorePrivileges() {
        return ignorePrivileges;
    }

    @Override
    public boolean isKeepNewlines() {
        return keepNewlines;
    }

    @Override
    public Collection<DbObjType> getAllowedTypes() {
        return Collections.unmodifiableCollection(allowedTypes);
    }

    @Override
    public boolean isGenerateExists() {
        return generateExists;
    }

    @Override
    public boolean isGenerateExistDoBlock() {
        return generateExistDoBlock;
    }

    @Override
    public boolean isDropBeforeCreate() {
        return dropBeforeCreate;
    }

    @Override
    public boolean isCommentsToEnd() {
        return commentsToEnd;
    }

    public Collection<DbObjType> getGraphFilterTypes() {
        return Collections.unmodifiableCollection(graphFilterTypes);
    }

    public boolean isGraphInvertFilter() {
        return graphInvertFilter;
    }

    @Override
    public boolean isSelectedOnly() {
        return selectedOnly;
    }

    @Override
    public boolean isDataMovementMode() {
        return dataMovementMode;
    }

    @Override
    public boolean isPrintUsing() {
        return !usingTypeCastOff;
    }

    @Override
    public boolean isConcurrentlyMode() {
        return concurrentlyMode;
    }

    @Override
    public boolean isSimplifyView() {
        return simplifyView;
    }

    public int getGraphDepth() {
        return graphDepth;
    }

    public boolean isGraphReverse() {
        return graphReverse;
    }

    public boolean isProjUpdate() {
        return projUpdate;
    }

    public Collection<String> getGraphNames() {
        return Collections.unmodifiableCollection(graphNames);
    }

    @Override
    public Collection<String> getPreFilePath() {
        return Collections.unmodifiableCollection(preFilePath);
    }

    @Override
    public Collection<String> getPostFilePath() {
        return Collections.unmodifiableCollection(postFilePath);
    }

    @Override
    public boolean isAutoFormatObjectCode() {
        return false;
    }

    @Override
    public FormatConfiguration getFormatConfiguration() {
        return null;
    }

    public IDatabaseProvider getProvider() {
        return provider;
    }

    @Override
    public CliArgs copy() {
        var args = new CliArgs();
        args.addTransaction = addTransaction;
        args.allowedDangers = allowedDangers;
        args.allowedTypes = allowedTypes;
        args.clearLibCache = clearLibCache;
        args.commentsToEnd = commentsToEnd;
        args.concurrentlyMode = concurrentlyMode;
        args.dataMovementMode = dataMovementMode;
        args.dbType = dbType;
        args.disableCheckFunctionBodies = disableCheckFunctionBodies;
        args.dropBeforeCreate = dropBeforeCreate;
        args.enableFunctionBodiesDependencies = enableFunctionBodiesDependencies;
        args.generateConstraintNotValid = generateConstraintNotValid;
        args.generateExistDoBlock = generateExistDoBlock;
        args.generateExists = generateExists;
        args.graphDepth = graphDepth;
        args.graphFilterTypes = graphFilterTypes;
        args.graphInvertFilter = graphInvertFilter;
        args.graphNames = graphNames;
        args.graphReverse = graphReverse;
        args.ignoreColumnOrder = ignoreColumnOrder;
        args.ignoreConcurrentModification = ignoreConcurrentModification;
        args.ignoreErrors = ignoreErrors;
        args.ignoreLists = ignoreLists;
        args.ignorePrivileges = ignorePrivileges;
        args.ignoreSchemaList = ignoreSchemaList;
        args.inCharsetName = inCharsetName;
        args.keepNewlines = keepNewlines;
        args.libSafeMode = libSafeMode;
        args.mode = mode;
        args.newSrc = newSrc;
        args.oldSrc = oldSrc;
        args.outCharsetName = outCharsetName;
        args.outputTarget = outputTarget;
        args.postFilePath = postFilePath;
        args.preFilePath = preFilePath;
        args.projUpdate = projUpdate;
        args.runOnDb = runOnDb;
        args.runOnTarget = runOnTarget;
        args.safeMode = safeMode;
        args.selectedOnly = selectedOnly;
        args.simplifyView = simplifyView;
        args.sourceLibs = sourceLibs;
        args.sourceLibsWithoutPriv = sourceLibsWithoutPriv;
        args.sourceLibXmls = sourceLibXmls;
        args.stopNotAllowed = stopNotAllowed;
        args.targetLibs = targetLibs;
        args.targetLibsWithoutPriv = targetLibsWithoutPriv;
        args.targetLibXmls = targetLibXmls;
        args.timeZone = timeZone;
        args.usingTypeCastOff = usingTypeCastOff;
        args.provider = provider;
        return args;
    }

    @Override
    public void setIgnorePrivileges(boolean ignorePrivileges) {
        this.ignorePrivileges = ignorePrivileges;
    }

    /**
     * Parses command line arguments or outputs instructions.
     *
     * @param args   array of arguments
     *
     * @return true if arguments were parsed and execution can continue,
     *         otherwise false
     */
    public boolean parse(String[] args) throws CmdLineException {
        if (args.length != 0) {
            new CmdLineParser(this).parseArgument(args);
        } else {
            // show help instead of failing for 0 args
            zhelp = true;
        }
        if (zhelp) {
            printUsage();
            return false;
        }
        if (zversion) {
            printVersion();
            return false;
        }
        if (zlistCharsets) {
            listCharsets();
            return false;
        }

        if (clearLibCache && CliMode.DIFF == mode && (oldSrc == null || newSrc == null)) {
            return true;
        }

        checkModeParams();
        checkDbTypesParam();
        checkParams();

        if (CliMode.DIFF == mode) {
            if (oldSrc == null || newSrc == null) {
                badArgs(Messages.CliArgs_error_source_dest);
            }
        } else if (CliMode.PARSE == mode && projUpdate) {
            oldSrc = outputTarget;
        }

        createProvider();
        return true;
    }

    private void createProvider() {
        provider = switch (dbType) {
            case PG -> new PgDatabaseProvider();
            case MS -> new MsDatabaseProvider();
            case CH -> new ChDatabaseProvider();
        };
    }

    private void checkParams() throws CmdLineException {
        if (newSrc == null) {
            badArgs(Messages.CliArgs_error_source_null);
        }

        if (CliMode.DIFF == mode) {
            if (dbType == DatabaseType.PG && addTransaction && concurrentlyMode) {
                badArgs(Messages.CliArgs_error_concurrently_mode_wrong_option);
            }
            if (runOnTarget && !oldSrc.startsWith(URL_START_JDBC)) {
                badArgs(Messages.CliArgs_error_target_non_db);
            }
            if (runOnDb != null && !runOnDb.startsWith(URL_START_JDBC)) {
                badArgs(Messages.CliArgs_error_run_on_non_jdbc);
            }
        } else if (CliMode.PARSE == mode && outputTarget == null) {
            badArgs(Messages.CliArgs_error_argument_null.formatted("\"-o (--output)\"")); //$NON-NLS-1$
        }
    }

    private void checkModeParams() throws CmdLineException {
        // argument can be used only with mode
        badArgWithCorrectModes(newSrc != null, "-s (--source)", CliMode.DIFF, CliMode.PARSE, CliMode.GRAPH); //$NON-NLS-1$
        badArgWithCorrectModes(oldSrc != null, "-t (--target)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(addTransaction, "-X (--add-transaction)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(runOnDb != null, "-R (--run-on)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(enableFunctionBodiesDependencies, "-f (--enable-function-bodies-dependencies)", //$NON-NLS-1$
                CliMode.DIFF, CliMode.PARSE, CliMode.GRAPH);
        badArgWithCorrectModes(simplifyView, "--simplify-views", CliMode.DIFF, CliMode.PARSE); //$NON-NLS-1$
        badArgWithCorrectModes(timeZone != null, "-Z (--time-zone)", CliMode.DIFF, CliMode.PARSE); //$NON-NLS-1$
        badArgWithCorrectModes(!allowedTypes.isEmpty(), "-O (--allowed-object)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!ignoreLists.isEmpty(), "-I (--ignore-list)", CliMode.DIFF, CliMode.PARSE); //$NON-NLS-1$
        badArgWithCorrectModes(selectedOnly, "--selected-only", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(runOnTarget, "-r (--run-on-target)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(disableCheckFunctionBodies, "-F (--no-check-function-bodies)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!preFilePath.isEmpty(), "--pre-script", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!postFilePath.isEmpty(), "--post-script", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(ignoreColumnOrder, "--ignore-column-order", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(generateConstraintNotValid, "-v (--generate-constraint-not-valid)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(usingTypeCastOff, "--using-off", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(dataMovementMode, "--migrate-data", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(concurrentlyMode, "-C (--concurrently-mode)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(generateExists, "--generate-exist", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(generateExistDoBlock, "-do (--generate-exist-do-block)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(dropBeforeCreate, "--drop-before-create", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(commentsToEnd, "--comments-to-end", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(safeMode, "-S (--safe-mode)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!allowedDangers.isEmpty(), "-D (--allow-danger-ddl)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(stopNotAllowed, "--stop-not-allowed", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!sourceLibXmls.isEmpty(), "--tgt-lib-xml", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!sourceLibs.isEmpty(), "--tgt-lib", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!sourceLibsWithoutPriv.isEmpty(), "--tgt-lib-no-priv", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(projUpdate, "--update-project", CliMode.PARSE); //$NON-NLS-1$
        badArgWithCorrectModes(!graphNames.isEmpty(), "--graph-name", CliMode.GRAPH); //$NON-NLS-1$
        badArgWithCorrectModes(DEFAULT_DEPTH != graphDepth, "--graph-depth", CliMode.GRAPH); //$NON-NLS-1$
        badArgWithCorrectModes(!graphFilterTypes.isEmpty(), "--graph-filter-object", CliMode.GRAPH); //$NON-NLS-1$
    }

    private void checkDbTypesParam() throws CmdLineException {
        badArgWithWrongDbType(simplifyView, "--simplify-views", DatabaseType.MS, DatabaseType.CH); //$NON-NLS-1$
        badArgWithWrongDbType(timeZone != null, "-Z (--time-zone)", DatabaseType.MS, DatabaseType.CH); //$NON-NLS-1$
        badArgWithWrongDbType(generateExistDoBlock, "-do (--generate-exist-do-block)", DatabaseType.MS, //$NON-NLS-1$
                DatabaseType.CH);
        badArgWithWrongDbType(concurrentlyMode, "-C (--concurrently-mode)", DatabaseType.CH); //$NON-NLS-1$
        badArgWithWrongDbType(commentsToEnd, "--comments-to-end", DatabaseType.CH); //$NON-NLS-1$
    }

    private void badArgWithCorrectModes(boolean condition, String param, CliMode... modes) throws CmdLineException {
        if (condition && !containsInArray(mode, modes)) {
            badArgs(Messages.CliArgs_error_wrong_mode.formatted(param, mode));
        }
    }

    private void badArgWithWrongDbType(boolean condition, String arg, DatabaseType... wrongDbTypes)
            throws CmdLineException {
        if (condition && containsInArray(dbType, wrongDbTypes)) {
            badArgs(Messages.CliArgs_error_wrong_db_type.formatted(arg, dbType));
        }
    }

    private void badArgs(String message) throws CmdLineException {
        throw new CmdLineException(null, message, null);
    }

    private void printUsage() {
        // fix defaults for options like help and other 0-arg booleans
        OptionHandlerRegistry.getRegistry().registerHandler(Boolean.class, BooleanNoDefOptionHandler.class);
        OptionHandlerRegistry.getRegistry().registerHandler(boolean.class, BooleanNoDefOptionHandler.class);

        ParserProperties prop = ParserProperties.defaults().withUsageWidth(80).withOptionSorter(null);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // new args instance to get correct defaults
        new CmdLineParser(new CliArgs(), prop).printUsage(new OutputStreamWriter(buf, StandardCharsets.UTF_8),
                new CliArgsLocalizationsBundle());

        writeToConsole(Messages.UsageHelp.replace("${tab}", "\t").formatted( //$NON-NLS-1$ //$NON-NLS-2$
                buf.toString(StandardCharsets.UTF_8),
                DangerStatementOptionHandler.getMetaVariable() + '\n' + DbObjTypeOptionHandler.getMetaVariable()));
    }

    private void writeToConsole(String message) {
        System.out.println(message);
    }

    private void printVersion() {
        writeToConsole(Utils.getVersion());
    }

    private void listCharsets() {
        Charset.availableCharsets().keySet().forEach(this::writeToConsole);
    }

    private <T> boolean containsInArray(T element, T[] elements) {
        for (T t : elements) {
            if (t == element) {
                return true;
            }
        }

        return false;
    }
}