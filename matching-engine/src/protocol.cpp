#include "me/protocol.hpp"

#include <algorithm>
#include <cctype>
#include <sstream>

namespace me {

std::optional<int64_t> parse_price(const std::string& s) {
    if (s.empty()) return std::nullopt;
    int64_t whole = 0, frac = 0;
    int frac_digits = 0;
    bool seen_dot = false, any_digit = false;
    for (char c : s) {
        if (c == '.') {
            if (seen_dot) return std::nullopt;
            seen_dot = true;
        } else if (std::isdigit(static_cast<unsigned char>(c))) {
            any_digit = true;
            if (!seen_dot) {
                whole = whole * 10 + (c - '0');
                if (whole > 1'000'000'000) return std::nullopt;  // sanity limit
            } else {
                if (++frac_digits > 2) return std::nullopt;  // no sub-cent prices
                frac = frac * 10 + (c - '0');
            }
        } else {
            return std::nullopt;
        }
    }
    if (!any_digit) return std::nullopt;
    if (frac_digits == 1) frac *= 10;
    return whole * 100 + frac;
}

std::string format_price(int64_t cents) {
    std::string out = std::to_string(cents / 100) + ".";
    int64_t c = cents % 100;
    if (c < 10) out += "0";
    return out + std::to_string(c);
}

static std::optional<int64_t> parse_int(const std::string& s) {
    if (s.empty() || s.size() > 15) return std::nullopt;
    int64_t v = 0;
    for (char c : s) {
        if (!std::isdigit(static_cast<unsigned char>(c))) return std::nullopt;
        v = v * 10 + (c - '0');
    }
    return v;
}

static std::vector<std::string> split(const std::string& line) {
    std::istringstream in(line);
    std::vector<std::string> parts;
    std::string tok;
    while (in >> tok) parts.push_back(tok);
    return parts;
}

static std::string upper(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return std::toupper(c); });
    return s;
}

static std::vector<std::string> reject(const std::string& id, const std::string& why) {
    return {"REJECT " + id + " " + why, "OK"};
}

std::vector<std::string> handle_line(Engine& engine, const std::string& line) {
    auto p = split(line);
    if (p.empty()) return {"ERR empty_command", "OK"};
    std::string cmd = upper(p[0]);

    if (cmd == "PING") return {"PONG", "OK"};

    if (cmd == "NEW") {
        if (p.size() != 7) return {"ERR usage_NEW_id_symbol_side_type_qty_price", "OK"};
        auto id = parse_int(p[1]);
        if (!id || *id == 0) return {"ERR bad_id", "OK"};
        std::string ids = p[1];
        std::string symbol = upper(p[2]);
        std::string side = upper(p[3]), type = upper(p[4]);
        auto qty = parse_int(p[5]);
        auto price = parse_price(p[6]);

        if (side != "BUY" && side != "SELL") return reject(ids, "bad_side");
        if (type != "LIMIT" && type != "MARKET") return reject(ids, "bad_type");
        if (!qty) return reject(ids, "bad_qty");
        if (!price) return reject(ids, "bad_price");

        Order o;
        o.id = static_cast<uint64_t>(*id);
        o.side = side == "BUY" ? Side::Buy : Side::Sell;
        o.type = type == "LIMIT" ? OrderType::Limit : OrderType::Market;
        o.price = *price;
        o.remaining = *qty;

        Result r = engine.submit(symbol, o);
        if (r.status == Status::Rejected) {
            std::string why = r.reason;
            std::replace(why.begin(), why.end(), ' ', '_');
            return reject(ids, why);
        }
        std::vector<std::string> out;
        for (const auto& t : r.trades) {
            out.push_back("TRADE " + std::to_string(t.buy_id) + " " + std::to_string(t.sell_id) + " " + symbol + " " +
                          format_price(t.price) + " " + std::to_string(t.qty));
        }
        out.push_back("ACK " + ids + (r.resting ? " RESTING " : " DONE ") + std::to_string(r.remaining));
        out.push_back("OK");
        return out;
    }

    if (cmd == "CANCEL") {
        if (p.size() != 3) return {"ERR usage_CANCEL_id_symbol", "OK"};
        auto id = parse_int(p[1]);
        if (!id) return {"ERR bad_id", "OK"};
        Result r = engine.cancel(upper(p[2]), static_cast<uint64_t>(*id));
        if (r.status == Status::Canceled) return {"CANCELED " + p[1] + " " + std::to_string(r.remaining), "OK"};
        return {"NOTFOUND " + p[1], "OK"};
    }

    if (cmd == "BOOK") {
        if (p.size() < 2 || p.size() > 3) return {"ERR usage_BOOK_symbol_depth", "OK"};
        size_t depth = 5;
        if (p.size() == 3) {
            auto d = parse_int(p[2]);
            if (!d || *d == 0 || *d > 100) return {"ERR bad_depth", "OK"};
            depth = static_cast<size_t>(*d);
        }
        auto snap = engine.snapshot(upper(p[1]), depth);
        std::vector<std::string> out;
        for (const auto& l : snap.bids)
            out.push_back("LEVEL BID " + format_price(l.price) + " " + std::to_string(l.qty) + " " + std::to_string(l.orders));
        for (const auto& l : snap.asks)
            out.push_back("LEVEL ASK " + format_price(l.price) + " " + std::to_string(l.qty) + " " + std::to_string(l.orders));
        out.push_back("OK");
        return out;
    }

    return {"ERR unknown_command", "OK"};
}

}  // namespace me
