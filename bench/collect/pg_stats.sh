#!/usr/bin/env bash
# Số liệu phía PostgreSQL của một lần chạy.
#
#   bash bench/collect/pg_stats.sh reset          đưa thống kê về 0 ngay trước khi k6 chạy
#   bash bench/collect/pg_stats.sh dump <thư mục>  xuất CSV sau khi app đã dừng (backend đã đẩy hết thống kê)
#
# Xuất: pg_stat_statements (câu lệnh tốn thời gian nhất), pg_stat_database, pg_stat_user_tables,
# pg_statio_user_tables (gồm khối TOAST, nơi chứa cột data lớn), pg_stat_bgwriter, kích thước bảng.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../kaggle/env.sh"

action=${1:?"cách dùng: pg_stats.sh reset | dump <thư mục>"}
case "$action" in
  reset)
    pg_admin -d postgres -c "SELECT pg_stat_statements_reset()" -c "SELECT pg_stat_reset_shared('bgwriter')" > /dev/null
    pg_admin -d "$DB_RUN" -c "SELECT pg_stat_reset()" > /dev/null
    ;;
  dump)
    out=${2:?"thiếu thư mục"}
    mkdir -p "$out"
    pg_bench -d postgres << EOF
\\copy (SELECT queryid, calls, round(total_exec_time::numeric, 3) AS total_exec_ms, round(mean_exec_time::numeric, 4) AS mean_exec_ms, rows, shared_blks_hit, shared_blks_read, shared_blks_dirtied, shared_blks_written, temp_blks_written, round(blk_read_time::numeric, 3) AS blk_read_ms, wal_bytes, regexp_replace(query, '\s+', ' ', 'g') AS query FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$DB_RUN') ORDER BY total_exec_time DESC) TO '$out/pg_stat_statements.csv' CSV HEADER
\\copy (SELECT checkpoints_timed, checkpoints_req, buffers_checkpoint, buffers_clean, buffers_backend, buffers_alloc FROM pg_stat_bgwriter) TO '$out/pg_stat_bgwriter.csv' CSV HEADER
\\copy (SELECT xact_commit, xact_rollback, blks_read, blks_hit, tup_returned, tup_fetched, tup_inserted, tup_updated, tup_deleted, temp_bytes, deadlocks, round(blk_read_time::numeric, 3) AS blk_read_ms, round(active_time::numeric, 3) AS active_ms FROM pg_stat_database WHERE datname = '$DB_RUN') TO '$out/pg_stat_database.csv' CSV HEADER
EOF
    pg_bench -d "$DB_RUN" << EOF
\\copy (SELECT relname, seq_scan, seq_tup_read, idx_scan, idx_tup_fetch, n_tup_ins, n_tup_upd, n_tup_hot_upd, n_tup_del, n_live_tup, n_dead_tup, autovacuum_count, autoanalyze_count FROM pg_stat_user_tables ORDER BY relname) TO '$out/pg_stat_user_tables.csv' CSV HEADER
\\copy (SELECT relname, heap_blks_read, heap_blks_hit, idx_blks_read, idx_blks_hit, toast_blks_read, toast_blks_hit, tidx_blks_read, tidx_blks_hit FROM pg_statio_user_tables ORDER BY relname) TO '$out/pg_statio_user_tables.csv' CSV HEADER
\\copy (SELECT c.relname, pg_total_relation_size(c.oid) AS total_bytes, pg_relation_size(c.oid) AS heap_bytes, COALESCE(pg_total_relation_size(c.reltoastrelid), 0) AS toast_bytes FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind = 'r' ORDER BY 2 DESC) TO '$out/pg_table_sizes.csv' CSV HEADER
EOF
    ;;
  *)
    die "hành động không hợp lệ: $action"
    ;;
esac
