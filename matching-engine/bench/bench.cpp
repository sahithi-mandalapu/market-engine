// quick benchmark of the order book on its own (no sockets).
// usage: me_bench [num_orders]
// Mix: ~70% limit orders near the mid, ~15% market orders, ~15% cancels of random live orders.

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "me/orderbook.hpp"

using clk = std::chrono::steady_clock;

int main(int argc, char** argv) {
    long n = argc > 1 ? std::atol(argv[1]) : 1'000'000;
    me::OrderBook book;

    uint64_t x = 88172645463325252ull;  // xorshift, deterministic so runs are comparable
    auto rnd = [&]() { x ^= x << 13; x ^= x >> 7; x ^= x << 17; return x; };

    std::vector<uint64_t> live;  // ids we might cancel (some will already be filled, that's fine)
    live.reserve(1 << 20);
    std::vector<double> lat;
    lat.reserve(static_cast<size_t>(n));

    long trades = 0;
    uint64_t id = 1;
    auto start = clk::now();
    for (long i = 0; i < n; i++) {
        uint64_t roll = rnd() % 100;
        auto t0 = clk::now();
        if (roll < 70) {
            me::Order o;
            o.id = id++;
            o.side = (rnd() & 1) ? me::Side::Buy : me::Side::Sell;
            o.type = me::OrderType::Limit;
            o.price = 10000 + static_cast<int64_t>(rnd() % 41) - 20 + (o.side == me::Side::Buy ? -2 : 2);
            o.remaining = 1 + static_cast<int64_t>(rnd() % 100);
            auto r = book.submit(o);
            trades += static_cast<long>(r.trades.size());
            if (r.resting) live.push_back(o.id);
        } else if (roll < 85) {
            me::Order o;
            o.id = id++;
            o.side = (rnd() & 1) ? me::Side::Buy : me::Side::Sell;
            o.type = me::OrderType::Market;
            o.remaining = 1 + static_cast<int64_t>(rnd() % 100);
            trades += static_cast<long>(book.submit(o).trades.size());
        } else if (!live.empty()) {
            size_t k = rnd() % live.size();
            book.cancel(live[k]);
            live[k] = live.back();
            live.pop_back();
        }
        lat.push_back(std::chrono::duration<double, std::micro>(clk::now() - t0).count());
    }
    double secs = std::chrono::duration<double>(clk::now() - start).count();

    std::sort(lat.begin(), lat.end());
    auto pct = [&](double p) { return lat[static_cast<size_t>(p * (lat.size() - 1))]; };

    std::printf("operations:   %ld\n", n);
    std::printf("trades:       %ld\n", trades);
    std::printf("resting now:  %zu orders\n", book.order_count());
    std::printf("throughput:   %.0f ops/sec\n", static_cast<double>(n) / secs);
    std::printf("latency us:   p50=%.2f  p99=%.2f  p99.9=%.2f  max=%.2f\n", pct(0.50), pct(0.99), pct(0.999), lat.back());
    return 0;
}
