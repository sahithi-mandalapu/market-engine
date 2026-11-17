#include <gtest/gtest.h>

#include <atomic>
#include <thread>
#include <vector>

#include "me/engine.hpp"

using namespace me;

TEST(Engine, SymbolsAreIndependent) {
    Engine e;
    e.submit("AAPL", Order{1, Side::Sell, OrderType::Limit, 10000, 10});
    // same price on a different ticker must not match
    auto r = e.submit("MSFT", Order{2, Side::Buy, OrderType::Limit, 10000, 10});
    EXPECT_TRUE(r.trades.empty());
    EXPECT_EQ(e.symbol_count(), 2u);
    EXPECT_EQ(e.snapshot("AAPL", 5).asks.size(), 1u);
    EXPECT_EQ(e.snapshot("MSFT", 5).bids.size(), 1u);
    EXPECT_TRUE(e.snapshot("NOPE", 5).bids.empty());
}

TEST(Engine, ConcurrentSubmitsStayConsistent) {
    // 4 threads hammer the SAME symbol with buys and sells. afterwards every share that was
    // bought must have been sold, and what's left resting should add up exactly.
    Engine e;
    const int threads = 4, per_thread = 5000;
    std::atomic<uint64_t> next_id{1};
    std::atomic<int64_t> traded{0};
    std::atomic<int64_t> submitted_buy{0}, submitted_sell{0};

    auto worker = [&](int seed) {
        uint32_t x = 777u + seed * 131;
        auto rnd = [&]() { x = x * 1664525u + 1013904223u; return x >> 8; };
        for (int i = 0; i < per_thread; i++) {
            Side s = (rnd() & 1) ? Side::Buy : Side::Sell;
            int64_t qty = 1 + rnd() % 20;
            int64_t price = 9950 + static_cast<int64_t>(rnd() % 11) * 10;
            auto r = e.submit("AAPL", Order{next_id++, s, OrderType::Limit, price, qty});
            (s == Side::Buy ? submitted_buy : submitted_sell) += qty;
            for (auto& t : r.trades) traded += t.qty;
        }
    };
    std::vector<std::thread> ts;
    for (int i = 0; i < threads; i++) ts.emplace_back(worker, i);
    for (auto& t : ts) t.join();

    auto snap = e.snapshot("AAPL", 100);
    int64_t resting_buy = 0, resting_sell = 0;
    for (auto& l : snap.bids) resting_buy += l.qty;
    for (auto& l : snap.asks) resting_sell += l.qty;
    // snapshot only holds 100 levels but we only used 11 price points, so that's everything
    EXPECT_EQ(submitted_buy.load(), traded.load() + resting_buy);
    EXPECT_EQ(submitted_sell.load(), traded.load() + resting_sell);
    if (!snap.bids.empty() && !snap.asks.empty()) {
        EXPECT_LT(snap.bids[0].price, snap.asks[0].price);
    }
}

TEST(Engine, DifferentSymbolsInParallel) {
    Engine e;
    const char* syms[] = {"AAPL", "MSFT", "NVDA", "TSLA"};
    std::atomic<uint64_t> next_id{1};
    std::vector<std::thread> ts;
    std::atomic<int64_t> trades{0};
    for (const char* s : syms) {
        ts.emplace_back([&, s]() {
            for (int i = 0; i < 2000; i++) {
                e.submit(s, Order{next_id++, Side::Sell, OrderType::Limit, 10000, 5});
                auto r = e.submit(s, Order{next_id++, Side::Buy, OrderType::Limit, 10000, 5});
                trades += static_cast<int64_t>(r.trades.size());
            }
        });
    }
    for (auto& t : ts) t.join();
    EXPECT_EQ(trades.load(), 4 * 2000);  // every buy found its sell
    EXPECT_EQ(e.symbol_count(), 4u);
}
