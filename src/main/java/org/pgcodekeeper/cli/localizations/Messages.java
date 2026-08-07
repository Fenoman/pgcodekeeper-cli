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
package org.pgcodekeeper.cli.localizations;

import java.lang.reflect.Field;
import java.util.ResourceBundle;

public class Messages {
    private static final String BUNDLE_NAME = "org.pgcodekeeper.cli.localizations.messages"; //$NON-NLS-1$

    public static String Batch_danger_reason;
    public static String Batch_error_args_not_strings;
    public static String Batch_error_duplicate_output_name;
    public static String Batch_error_duplicate_output_path;
    public static String Batch_error_in_section;
    public static String Batch_error_manifest_invalid;
    public static String Batch_error_manifest_read;
    public static String Batch_error_option_forbidden;
    public static String Batch_error_option_needs_value;
    public static String Batch_error_option_not_common;
    public static String Batch_error_option_not_output;
    public static String Batch_error_output_entry;
    public static String Batch_error_output_name_blank;
    public static String Batch_error_output_no_file;
    public static String Batch_error_outputs_empty;
    public static String Batch_error_positional_in_output;
    public static String Batch_error_root_object;
    public static String Batch_error_unknown_key;
    public static String Batch_section_common;
    public static String Batch_section_output;
    public static String Batch_summary_failed;
    public static String Batch_summary_ok;
    public static String CliArgs_add_transaction;
    public static String CliArgs_allow_danger_ddl;
    public static String CliArgs_allowed_object;
    public static String CliArgs_batch_manifest;
    public static String CliArgs_cluster_name;
    public static String CliArgs_clear_lib_cache;
    public static String CliArgs_comments_to_end;
    public static String CliArgs_concurrently_mode;
    public static String CliArgs_db_type;
    public static String CliArgs_disable_auto_load;
    public static String CliArgs_drop_before_create;
    public static String CliArgs_enable_function_bodies_dependencies;
    public static String CliArgs_error_argument_null;
    public static String CliArgs_error_batch_manifest_required;
    public static String CliArgs_error_concurrently_mode_wrong_option;
    public static String CliArgs_error_conflicting_options;
    public static String CliArgs_error_jdbc_fetch_size_negative;
    public static String CliArgs_error_pg_catalog_cache_max_mb_non_positive;
    public static String CliArgs_error_pg_parallel_catalog_readers_negative;
    public static String CliArgs_error_project_file_filter;
    public static String CliArgs_error_pg_routine_body_residual_batch_bytes_non_positive;
    public static String CliArgs_error_pg_routine_body_residual_batch_count_non_positive;
    public static String CliArgs_error_run_on_non_jdbc;
    public static String CliArgs_error_source_dest;
    public static String CliArgs_error_source_null;
    public static String CliArgs_error_space_joined_option;
    public static String CliArgs_error_structure_file_with_update;
    public static String CliArgs_error_target_non_db;
    public static String CliArgs_error_wrong_db_type;
    public static String CliArgs_error_wrong_mode;
    public static String CliArgs_generate_constraint_not_valid;
    public static String CliArgs_generate_exist;
    public static String CliArgs_generate_exist_do_block;
    public static String CliArgs_graph_depth;
    public static String CliArgs_graph_filter_object;
    public static String CliArgs_graph_invert_filter;
    public static String CliArgs_graph_name;
    public static String CliArgs_graph_reverse;
    public static String CliArgs_Help;
    public static String CliArgs_ignore_column_order;
    public static String CliArgs_ignore_column_statistics;
    public static String CliArgs_ignore_concurrent_modification;
    public static String CliArgs_ignore_errors;
    public static String CliArgs_ignore_list;
    public static String CliArgs_ignore_schema;
    public static String CliArgs_ignore_sequence_cache;
    public static String CliArgs_in_charset;
    public static String CliArgs_jdbc_fetch_size;
    public static String CliArgs_keep_newlines;
    public static String CliArgs_lib_safe_mode;
    public static String CliArgs_list_charsets;
    public static String CliArgs_migrate_data;
    public static String CliArgs_mode;
    public static String CliArgs_no_alter_table_only;
    public static String CliArgs_no_check_function_bodies;
    public static String CliArgs_no_parallel_load;
    public static String CliArgs_no_privileges;
    public static String CliArgs_out_charset;
    public static String CliArgs_output;
    public static String CliArgs_pg_catalog_cache_dir;
    public static String CliArgs_pg_catalog_cache_max_mb;
    public static String CliArgs_pg_catalog_cache_rows;
    public static String CliArgs_pg_parallel_catalog_readers;
    public static String CliArgs_pg_routine_body_hash_first;
    public static String CliArgs_pg_routine_body_no_hash_first;
    public static String CliArgs_pg_routine_body_no_skip_matched_analysis;
    public static String CliArgs_pg_routine_body_residual_batch_bytes;
    public static String CliArgs_pg_routine_body_residual_batch_count;
    public static String CliArgs_post_script;
    public static String CliArgs_pre_script;
    public static String CliArgs_project_file_filter;
    public static String CliArgs_run_on;
    public static String CliArgs_run_on_target;
    public static String CliArgs_safe_mode;
    public static String CliArgs_selected_only;
    public static String CliArgs_show_error;
    public static String CliArgs_simplify_not_null;
    public static String CliArgs_simplify_views;
    public static String CliArgs_source;
    public static String CliArgs_src_lib;
    public static String CliArgs_src_lib_no_priv;
    public static String CliArgs_src_lib_xml;
    public static String CliArgs_stop_not_allowed;
    public static String CliArgs_structure_file;
    public static String CliArgs_target;
    public static String CliArgs_tgt_lib;
    public static String CliArgs_tgt_lib_no_priv;
    public static String CliArgs_tgt_lib_xml;
    public static String CliArgs_time_zone;
    public static String CliArgs_update_project;
    public static String CliArgs_use_actual_syntax;
    public static String CliArgs_use_parallel_load;
    public static String CliArgs_using_off;
    public static String CliArgs_Version;
    public static String Main_cach_clear;
    public static String Main_danger_statements;
    public static String Main_log_apply_migration_script;
    public static String CliArgs_deps_file;
    public static String Main_log_build_graph_deps;
    public static String Main_log_cache_dir_absent;
    public static String Main_log_cache_mode;
    public static String Main_log_contains_dangerous_statements;
    public static String Main_log_create_script;
    public static String Main_log_running_error;
    public static String Main_log_start_export_proj;
    public static String Main_log_start_update_proj;
    public static String Main_log_succes_finish;
    public static String Main_show_stacktrace;
    public static String PgDiffCli_error_while_load_database;
    public static String UsageHelp;

    static {
        ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME);
        for (String key : bundle.keySet()) {
            try {
                Field field = Messages.class.getField(key);
                if (field.getType().equals(String.class)) {
                    field.set(null, bundle.getString(key));
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // ignore
            }
        }
    }

    private Messages() {
    }
}
