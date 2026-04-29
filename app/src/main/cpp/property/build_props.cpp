#include "property/build_props.h"

#include <sstream>

namespace avm::property {

namespace {
std::string trim(const std::string& v) {
    size_t b = 0;
    while (b < v.size() && (v[b] == ' ' || v[b] == '\t' || v[b] == '\r')) ++b;
    size_t e = v.size();
    while (e > b && (v[e - 1] == ' ' || v[e - 1] == '\t' || v[e - 1] == '\r')) --e;
    return v.substr(b, e - b);
}
}  // namespace

std::vector<std::pair<std::string, std::string>> parseBuildProps(const std::string& contents) {
    std::vector<std::pair<std::string, std::string>> out;
    std::istringstream in(contents);
    std::string line;
    while (std::getline(in, line)) {
        const std::string trimmed = trim(line);
        if (trimmed.empty() || trimmed[0] == '#') continue;
        const size_t eq = trimmed.find('=');
        if (eq == std::string::npos) continue;
        std::string key = trim(trimmed.substr(0, eq));
        std::string value = trim(trimmed.substr(eq + 1));
        if (key.empty()) continue;
        out.emplace_back(std::move(key), std::move(value));
    }
    return out;
}

}  // namespace avm::property
