// Policy-only witness, not a DuckDB build or full upstream test-suite execution.
// DuckDB d54cbbe3df17e8e5ce68312f483b4155836112aa, test/sqlite/sqllogic_command.cpp:343-358.
// Full file SHA256: c871916a74bf247511eebfcded9cc7979b74ba4ac15f5bace2dc83504ece4cbf.
#include <cassert>
#include <iostream>
#include <vector>

static bool should_run(const std::vector<bool> &conditions, bool skip_if) {
    for (bool condition_holds : conditions) {
        if (condition_holds) {
            if (skip_if) return false;
        } else {
            if (!skip_if) return false;
        }
    }
    return true;
}

int main() {
    // In FF, FT, TF, TT order. These are explicit expectations, not generated goldens.
    const bool expected_skip[] = {true, false, false, false};
    const bool expected_only[] = {false, false, false, true};
    std::cout << "first,second,skipif_runs,onlyif_runs\n";
    for (int index = 0; index != 4; ++index) {
        const bool first = (index & 2) != 0;
        const bool second = (index & 1) != 0;
        const bool skip = should_run({first, second}, true);
        const bool only = should_run({first, second}, false);
        assert(skip == expected_skip[index]);
        assert(only == expected_only[index]);
        std::cout << first << ',' << second << ',' << skip << ',' << only << '\n';
    }
}
