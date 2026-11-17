#pragma once
// Many symbols, one OrderBook each. Every book has its own mutex, so orders for different
// tickers never block each other, only orders for the same ticker are serialized
// (which is what you want, matching has to be sequential per book).

#include <memory>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <unordered_map>

#include "me/orderbook.hpp"

namespace me {

struct BookSnapshot {
    std::vector<Level> bids;
    std::vector<Level> asks;
};

class Engine {
public:
    Result submit(const std::string& symbol, Order o);
    Result cancel(const std::string& symbol, uint64_t id);
    BookSnapshot snapshot(const std::string& symbol, size_t depth);
    size_t symbol_count() const;

private:
    struct Entry {
        OrderBook book;
        std::mutex mu;
    };

    Entry* get_or_create(const std::string& symbol);
    Entry* find(const std::string& symbol);

    mutable std::shared_mutex map_mu_;
    std::unordered_map<std::string, std::unique_ptr<Entry>> books_;
};

}  // namespace me
