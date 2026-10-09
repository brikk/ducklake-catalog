"""Matched native preview peers in separate processes; bounded startup and teardown."""
import json
import multiprocessing
import socket
import uuid

import duckdb


def provenance(connection):
    return {
        "core": connection.execute("PRAGMA version").fetchall(),
        "extensions": connection.execute(
            "SELECT extension_name,extension_version FROM duckdb_extensions() WHERE extension_name='quack' AND loaded"
        ).fetchall(),
    }


def serve(pipe, stop, port, token):
    try:
        with duckdb.connect() as connection:
            connection.execute("INSTALL quack; LOAD quack")
            connection.execute("CREATE TABLE recovery(i INTEGER); INSERT INTO recovery VALUES (1)")
            connection.execute(f"SELECT * FROM quack_serve('quack://127.0.0.1:{port}/', token := '{token}')").fetchall()
            pipe.send(provenance(connection))
            stop.wait(60)
    except Exception as error:
        pipe.send({"error": str(error).splitlines()[0]})
    finally:
        pipe.close()


def main():
    context = multiprocessing.get_context("spawn")
    receive, send = context.Pipe(duplex=False)
    stop = context.Event()
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    token = uuid.uuid4().hex
    server = context.Process(target=serve, args=(send, stop, port, token))
    server.start()
    try:
        assert receive.poll(30), "Quack server startup timed out"
        server_info = receive.recv()
        assert "error" not in server_info, server_info
        with duckdb.connect() as client:
            client.execute("INSTALL quack; LOAD quack")
            client.execute(f"ATTACH 'quack:127.0.0.1:{port}' AS rpc (TOKEN '{token}')")

            def rpc(sql):
                quoted = sql.replace("'", "''")
                return client.execute(f"CALL system.main.quack_query_by_name('rpc', '{quoted}')").fetchall()

            client_info = provenance(client)
            assert server_info == client_info, "Native client/server builds differ"
            rpc("BEGIN")
            rpc("INSERT INTO recovery VALUES (2)")
            rpc("ROLLBACK")
            assert rpc("SELECT i FROM recovery ORDER BY i") == [(1,)]
            rpc("BEGIN")
            try:
                rpc("SELECT * FROM missing_recovery_table")
            except duckdb.Error:
                pass
            else:
                raise AssertionError("Missing table unexpectedly succeeded")
            try:
                rpc("SELECT 1")
            except duckdb.Error as error:
                assert "aborted" in str(error), str(error)
            else:
                raise AssertionError("Failed explicit transaction was not invalidated")
            rpc("ROLLBACK")
            assert rpc("SELECT 1") == [(1,)]
            rpc("BEGIN")
            rpc("INSERT INTO recovery VALUES (3)")
            rpc("COMMIT")
            assert rpc("SELECT i FROM recovery ORDER BY i") == [(1,), (3,)]
            client.execute("DETACH rpc")
            print(json.dumps({"python_package": duckdb.__version__, "server": server_info,
                              "client": client_info, "rollback_recovery": True, "commit_reuse": True}, indent=2))
    finally:
        stop.set()
        server.join(5)
        if server.is_alive():
            server.terminate()
            server.join(5)
        receive.close()
        send.close()


if __name__ == "__main__":
    main()
