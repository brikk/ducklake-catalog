// Literal-gate-only witness, not an engine build or complete upstream comparison implementation.
// DuckDB d54cbbe3df17e8e5ce68312f483b4155836112aa, result_helper.cpp:549-559.
#include <cassert>
#include <functional>
#include <iostream>
#include <string>

static bool literal_gate(const std::string &actual, const std::string &raw,
                         const std::function<std::string(const std::string &)> &expand) {
    return actual == raw || actual == expand(raw);
}

int main() {
    int calls = 0;
    auto keywords = [&calls](const std::string &raw) {
        ++calls;
        if (raw == "${FOO}") return std::string("bar");
        if (raw == "${PAT}") return std::string("<REGEX>:.*");
        if (raw == "${N}") return std::string("1.0");
        if (raw == "${B}") return std::string("true");
        if (raw == "${HASH}") return std::string("1 values hashing to b026324c6904b2a9cb4b88d6d61c81d1");
        return raw;
    };
    assert(literal_gate("${FOO}", "${FOO}", keywords));
    assert(calls == 0); // Raw equality short-circuits even expensive/random keyword expansion.
    assert(literal_gate("bar", "${FOO}", keywords));
    assert(calls == 1);
    assert(literal_gate("${FOO}", "${FOO}", keywords) && literal_gate("bar", "${FOO}", keywords));
    assert(!literal_gate("wrong", "${FOO}", keywords));
    assert(!literal_gate("wrong", "${PAT}", keywords)); // Expanded text does not become a regex.
    assert(!literal_gate("1", "${N}", keywords)); // Numeric coercion later uses RAW ${N}, not 1.0.
    assert(!literal_gate("1", "${B}", keywords)); // Native BOOLEAN literal text is 1/0, not Java words.
    assert(!literal_gate("1", "${HASH}", keywords)); // Expanded text does not become a hash control.
    std::cout << "PASS raw/expanded literal alternatives, per-cell mixing and lazy/control boundaries\n";
}
