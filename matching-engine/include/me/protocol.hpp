#pragma once
// Text protocol so the Java side (or netcat) can talk to the engine. One command per line,
// the engine answers with zero or more lines and always ends with "OK".
//
//   NEW <id> <SYMBOL> <BUY|SELL> <LIMIT|MARKET> <qty> <price>
//        -> TRADE <buyId> <sellId> <SYMBOL> <price> <qty>      (0 or more)
//           ACK <id> <RESTING|DONE> <remaining>
//         or REJECT <id> <reason with underscores>
//   CANCEL <id> <SYMBOL>   -> CANCELED <id> <remaining>  |  NOTFOUND <id>
//   BOOK <SYMBOL> [depth]  -> LEVEL <BID|ASK> <price> <qty> <orders> ...
//   PING                   -> PONG
//
// price is a decimal string like 189.25 (max 2 decimals), it's turned into cents internally.
// For market orders the price field is still required, just put 0.

#include <optional>
#include <string>
#include <vector>

#include "me/engine.hpp"

namespace me {

std::optional<int64_t> parse_price(const std::string& s);
std::string format_price(int64_t cents);

// returns the reply lines, last one is always "OK"
std::vector<std::string> handle_line(Engine& engine, const std::string& line);

}  // namespace me
