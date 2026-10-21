# DuckDB CLI hosting a Quack RPC listener that serves as the remote DuckLake
# catalog backend in tests. Client side is the DuckDB JDBC driver running
# in-process inside our JVM; this container is the out-of-process DatabaseInstance
# that breaks the connection-id self-RPC loop documented upstream in
# duckdb/ducklake's scripts/run_quack_tests.py.
#
# Pinned to match duckdb_jdbc:2.0.0.0 on the client side. Bumping this version
# requires the matching bump in gradle/libs.versions.toml (key: `duckdb`).

FROM debian:bookworm-slim

ARG DUCKDB_VERSION=v2.0.0

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

# Detect arch via `uname -m` rather than relying on $TARGETARCH — the latter is
# a buildkit-only built-in and isn't reliably populated under podman / vanilla
# `docker build`. DuckDB's GitHub release assets use `amd64` / `arm64` (not
# `aarch64`).
RUN set -eu; \
    case "$(uname -m)" in \
    x86_64)  DUCKDB_ARCH=amd64; DUCKDB_SHA256=c6507206baf7793c6013dbd82368469a45f20dc0e17a6f92f204602f652ed2bc ;; \
    aarch64) DUCKDB_ARCH=arm64; DUCKDB_SHA256=859e1875d2a9515fbb4bf8871061437a14af82320da68c753f094c4b776348fe ;; \
        *) echo "unsupported arch: $(uname -m)" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://github.com/duckdb/duckdb/releases/download/${DUCKDB_VERSION}/duckdb-cli-linux-${DUCKDB_ARCH}.tar.gz" -o /tmp/duckdb.tar.gz; \
    echo "${DUCKDB_SHA256}  /tmp/duckdb.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/duckdb.tar.gz -C /usr/local/bin duckdb; \
    rm /tmp/duckdb.tar.gz; \
    chmod +x /usr/local/bin/duckdb

# Pre-pull the Quack extension at image build time so container startup doesn't
# pay the network round-trip. Quack ships as a core extension as of DuckDB
# 1.5.3 (announced 2026-05-20), so the stable repo is sufficient — no
# core_nightly or -unsigned flag required.
RUN duckdb -c "INSTALL quack;"

COPY entrypoint.sh /usr/local/bin/quack-entrypoint.sh
RUN chmod +x /usr/local/bin/quack-entrypoint.sh

EXPOSE 9494
ENTRYPOINT ["/usr/local/bin/quack-entrypoint.sh"]
