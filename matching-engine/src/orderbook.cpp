#include "me/orderbook.hpp"

#include <algorithm>

namespace me {

template <class Opposite>
void OrderBook::match(Order& in, Opposite& opp, std::vector<Trade>& trades) {
    while (in.remaining > 0 && !opp.empty()) {
        auto lvl = opp.begin();  // best price on the other side

        if (in.type == OrderType::Limit) {
            bool crosses = (in.side == Side::Buy) ? lvl->first <= in.price : lvl->first >= in.price;
            if (!crosses) break;
        }

        Queue& q = lvl->second;
        while (in.remaining > 0 && !q.empty()) {
            Order& resting = q.front();  // oldest order at this price
            int64_t fill = std::min(in.remaining, resting.remaining);
            in.remaining -= fill;
            resting.remaining -= fill;

            if (in.side == Side::Buy) {
                trades.push_back({in.id, resting.id, lvl->first, fill});
            } else {
                trades.push_back({resting.id, in.id, lvl->first, fill});
            }

            if (resting.remaining == 0) {
                index_.erase(resting.id);
                q.pop_front();
            }
        }
        if (q.empty()) opp.erase(lvl);
    }
}

Result OrderBook::submit(Order o) {
    Result r;
    if (o.remaining <= 0) {
        r.status = Status::Rejected;
        r.reason = "qty must be positive";
        return r;
    }
    if (o.type == OrderType::Limit && o.price <= 0) {
        r.status = Status::Rejected;
        r.reason = "limit price must be positive";
        return r;
    }
    if (index_.count(o.id)) {
        r.status = Status::Rejected;
        r.reason = "duplicate order id";
        return r;
    }

    if (o.side == Side::Buy) {
        match(o, asks_, r.trades);
    } else {
        match(o, bids_, r.trades);
    }

    r.remaining = o.remaining;
    if (o.remaining > 0 && o.type == OrderType::Limit) {
        if (o.side == Side::Buy) {
            auto& q = bids_[o.price];
            q.push_back(o);
            index_[o.id] = {Side::Buy, o.price, std::prev(q.end())};
        } else {
            auto& q = asks_[o.price];
            q.push_back(o);
            index_[o.id] = {Side::Sell, o.price, std::prev(q.end())};
        }
        r.resting = true;
    }
    return r;
}

Result OrderBook::cancel(uint64_t id) {
    Result r;
    auto found = index_.find(id);
    if (found == index_.end()) {
        r.status = Status::NotFound;
        return r;
    }
    Loc loc = found->second;
    r.remaining = loc.it->remaining;
    if (loc.side == Side::Buy) {
        auto lvl = bids_.find(loc.price);
        lvl->second.erase(loc.it);
        if (lvl->second.empty()) bids_.erase(lvl);
    } else {
        auto lvl = asks_.find(loc.price);
        lvl->second.erase(loc.it);
        if (lvl->second.empty()) asks_.erase(lvl);
    }
    index_.erase(found);
    r.status = Status::Canceled;
    return r;
}

std::optional<int64_t> OrderBook::best_bid() const {
    if (bids_.empty()) return std::nullopt;
    return bids_.begin()->first;
}

std::optional<int64_t> OrderBook::best_ask() const {
    if (asks_.empty()) return std::nullopt;
    return asks_.begin()->first;
}

template <class Map>
static std::vector<Level> collect(const Map& m, size_t depth) {
    std::vector<Level> out;
    for (const auto& [price, q] : m) {
        if (out.size() >= depth) break;
        int64_t total = 0;
        for (const auto& o : q) total += o.remaining;
        out.push_back({price, total, q.size()});
    }
    return out;
}

std::vector<Level> OrderBook::bids(size_t depth) const { return collect(bids_, depth); }
std::vector<Level> OrderBook::asks(size_t depth) const { return collect(asks_, depth); }

}  // namespace me
