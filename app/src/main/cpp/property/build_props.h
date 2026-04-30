#pragma once

#include <string>
#include <vector>

namespace avm::property {

/**
 * Parse `/system/build.prop` style content. The format is `key=value` per line, with `#`
 * comments and blank lines tolerated. Whitespace around `=` is trimmed.
 */
std::vector<std::pair<std::string, std::string>> parseBuildProps(const std::string& contents);

}  // namespace avm::property
