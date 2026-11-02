// Policy-only witness, not an engine build or complete upstream-suite execution.
// DuckDB d54cbbe3df17e8e5ce68312f483b4155836112aa:
// test/sqlite/sqllogic_test_runner.cpp:348-390 and src/common/string_util.cpp:596-629.
#include <cassert>
#include <iostream>
#include <string>
#include <utility>
#include <vector>

using Frames = std::vector<std::pair<std::string, std::string>>;

static std::string replace_all(const std::string &source, const std::string &from, const std::string &to) {
    std::string result;
    std::size_t previous = 0, next;
    while ((next = source.find(from, previous)) != std::string::npos) {
        result.append(source, previous, next - previous);
        result += to;
        previous = next + from.size();
    }
    result.append(source, previous, std::string::npos);
    return result;
}

static std::vector<std::string> comma_parts(const std::string &text) {
    std::vector<std::string> parts;
    std::size_t previous = 0, next;
    while ((next = text.find(',', previous)) != std::string::npos) {
        parts.push_back(text.substr(previous, next - previous));
        previous = next + 1;
    }
    parts.push_back(text.substr(previous));
    return parts;
}

static std::string replace_frames(std::string text, const Frames &frames) {
    for (const auto &frame : frames) {
        const auto names = comma_parts(frame.first);
        const auto values = comma_parts(frame.second);
        assert(names.size() == values.size());
        for (std::size_t i = 0; i < names.size(); ++i) {
            text = replace_all(text, "${" + names[i] + "}", values[i]);
            text = replace_all(text, "{" + names[i] + "}", values[i]);
        }
    }
    return text;
}

int main() {
    const Frames shadow {{"i", "1"}, {"i", "2"}};
    assert(replace_frames("SELECT ${i},{i}", shadow) == "SELECT 1,1");
    std::string condition_value;
    for (const auto &frame : shadow) if (frame.first == "i") condition_value = frame.second;
    assert(condition_value == "2"); // CheckLoopCondition scans all matching frames, without break.
    const Frames fresh {{"i", "{i}-outer"}, {"i", "inner"}};
    assert(replace_frames("SELECT '{i}'", fresh) == "SELECT 'inner-outer'");
    assert(replace_frames("SELECT '${i}'", fresh) == "SELECT 'inner-outer-outer'");
    const Frames comma {{"i,j", "1,outer"}, {"i,j", "2,inner"}};
    assert(replace_frames("SELECT {i},'{j}'", comma) == "SELECT 1,'outer'");
    std::cout << "PASS ordered frames, innermost lookup, fresh placeholders and comma iterators\n";
}
