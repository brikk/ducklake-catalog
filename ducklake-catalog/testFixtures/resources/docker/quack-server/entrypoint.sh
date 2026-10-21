#!/bin/sh
# Stand up a Quack RPC listener on 0.0.0.0:${QUACK_PORT} backed by an in-process
# DuckDB. The container's DuckDB hosts the DuckLake catalog metadata; clients
# attach via `ATTACH 'ducklake:quack:host:port'` from a separate DuckDB instance.
#
# Why the trailing `sleep infinity` inside the brace group: DuckDB's CLI runs in
# non-interactive batch mode when stdin is a pipe — it processes the script and
# exits on EOF, taking the listener down with it. Holding the writer side of the
# pipe open via `sleep infinity` blocks duckdb on read forever, keeping the
# listener alive. Container teardown (SIGTERM/SIGKILL) tears both processes down.

set -eu

PORT="${QUACK_PORT:-9494}"
TOKEN="${QUACK_TOKEN:-ducklake-test-token}"

{
    printf "LOAD quack;\n"
    # allow_other_hostname is required to bind to 0.0.0.0 — Quack refuses
    # non-localhost binds by default. Safe here because the container's network
    # namespace makes 0.0.0.0 mean "this container's interfaces only".
    # The host port is published ONLY on 127.0.0.1 by the fixture. DuckDB 2.0 defaults a
    # non-loopback bind to TLS, but clients correctly choose HTTP for that loopback endpoint.
    # This explicit plaintext mode is confined to the private test-container namespace;
    # production remote TLS defaults/signature verification are not changed.
    printf "SELECT * FROM quack_serve('quack://0.0.0.0:%s/', token := '%s', allow_other_hostname := true, disable_ssl := true);\n" "$PORT" "$TOKEN"
    exec sleep infinity
} | duckdb
