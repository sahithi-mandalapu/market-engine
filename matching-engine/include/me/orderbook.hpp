#pragma once
// Order book for ONE symbol. Price-time priority:
//   - better price first (highest bid / lowest ask)
//   - at the same price, whoever got there first
// Prices are integers in cents so we never compare doubles.

#include <cstdint>
#include <functional>
#include <list>
#include <map>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

namespace me {

enum class Side { Buy, Sell };
enum class OrderType { Limit, Market };

struct Order {
    uint64_t id = 0;
    Side side = Side::Buy;
    OrderType type = OrderType::Limit;
    int64_t price = 0;      // cents, ignored for market orders
    int64_t remaining = 0;  // shares left
};

struct Trade {
    uint64_t buy_id;
    uint64_t sell_id;
    int64_t price;  // always the price of the order that was already resting
    int64_t qty;
};

enum class Status { Accepted, Rejected, Canceled, NotFound };

struct Result {
    Status status = Status::Accepted;
    std::string reason;
    std::vector<Trade> trades;
    int64_t remaining = 0;  // unfilled shares after matching
    bool resting = false;   // true if what's left is now sitting in the book
};

struct Level {
    int64_t price;
    int64_t qty;
    size_t orders;
};

class OrderBook {
public:
    // Limit orders match anything that crosses, then the rest rests in the book.
    // Market orders match what they can and the remainder is dropped (IOC style),
    // they never rest.
    Result submit(Order o);
    Result cancel(uint64_t id);

    std::optional<int64_t> best_bid() const;
    std::optional<int64_t> best_ask() const;
    std::vector<Level> bids(size_t depth) const;
    std::vector<Level> asks(size_t depth) const;
    size_t order_count() const { return index_.size(); }

private:
    using Queue = std::list<Order>;  // list so iterators stay valid when we erase other orders

    struct Loc {
        Side side;
        int64_t price;
        Queue::iterator it;
    };

    template <class Opposite>
    void match(Order& incoming, Opposite& opposite, std::vector<Trade>& trades);

    std::map<int64_t, Queue, std::greater<int64_t>> bids_;
    std::map<int64_t, Queue> asks_;
    std::unordered_map<uint64_t, Loc> index_;  // id -> where it is, makes cancel cheap
};

}  // namespace me
