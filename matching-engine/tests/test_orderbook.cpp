#include <gtest/gtest.h>

#include "me/orderbook.hpp"

using namespace me;

static Order limit(uint64_t id, Side s, int64_t price, int64_t qty) {
    return Order{id, s, OrderType::Limit, price, qty};
}
static Order market(uint64_t id, Side s, int64_t qty) {
    return Order{id, s, OrderType::Market, 0, qty};
}

TEST(OrderBook, RestsWhenNothingToMatch) {
    OrderBook b;
    auto r = b.submit(limit(1, Side::Buy, 10000, 50));
    EXPECT_TRUE(r.trades.empty());
    EXPECT_TRUE(r.resting);
    EXPECT_EQ(r.remaining, 50);
    EXPECT_EQ(b.best_bid(), 10000);
    EXPECT_FALSE(b.best_ask().has_value());
}

TEST(OrderBook, FullFillAtRestingPrice) {
    OrderBook b;
    b.submit(limit(1, Side::Sell, 10000, 100));
    // buyer is willing to pay 101 but should get the resting 100.00 price
    auto r = b.submit(limit(2, Side::Buy, 10100, 100));
    ASSERT_EQ(r.trades.size(), 1u);
    EXPECT_EQ(r.trades[0].price, 10000);
    EXPECT_EQ(r.trades[0].qty, 100);
    EXPECT_EQ(r.trades[0].buy_id, 2u);
    EXPECT_EQ(r.trades[0].sell_id, 1u);
    EXPECT_FALSE(r.resting);
    EXPECT_EQ(b.order_count(), 0u);
}

TEST(OrderBook, PartialFillLeavesRemainderResting) {
    OrderBook b;
    b.submit(limit(1, Side::Sell, 10000, 30));
    auto r = b.submit(limit(2, Side::Buy, 10000, 100));
    ASSERT_EQ(r.trades.size(), 1u);
    EXPECT_EQ(r.trades[0].qty, 30);
    EXPECT_EQ(r.remaining, 70);
    EXPECT_TRUE(r.resting);
    EXPECT_EQ(b.best_bid(), 10000);
    EXPECT_FALSE(b.best_ask().has_value());
}

TEST(OrderBook, PriceTimePriority) {
    OrderBook b;
    b.submit(limit(1, Side::Sell, 10100, 10));  // worse price, earliest
    b.submit(limit(2, Side::Sell, 10000, 10));  // best price, first at that price
    b.submit(limit(3, Side::Sell, 10000, 10));  // best price, second at that price
    auto r = b.submit(market(4, Side::Buy, 25));
    ASSERT_EQ(r.trades.size(), 3u);
    EXPECT_EQ(r.trades[0].sell_id, 2u);  // price first, then time
    EXPECT_EQ(r.trades[1].sell_id, 3u);
    EXPECT_EQ(r.trades[2].sell_id, 1u);
    EXPECT_EQ(r.trades[2].qty, 5);
    EXPECT_EQ(r.trades[2].price, 10100);
}

TEST(OrderBook, SweepsMultipleLevels) {
    OrderBook b;
    b.submit(limit(1, Side::Buy, 9900, 10));
    b.submit(limit(2, Side::Buy, 9950, 10));
    b.submit(limit(3, Side::Buy, 10000, 10));
    auto r = b.submit(limit(4, Side::Sell, 9900, 25));
    ASSERT_EQ(r.trades.size(), 3u);
    EXPECT_EQ(r.trades[0].price, 10000);
    EXPECT_EQ(r.trades[1].price, 9950);
    EXPECT_EQ(r.trades[2].price, 9900);
    EXPECT_EQ(r.trades[2].qty, 5);
    EXPECT_EQ(b.best_bid(), 9900);  // 5 left of order 1
    EXPECT_EQ(b.bids(5)[0].qty, 5);
}

TEST(OrderBook, LimitDoesNotCrossPastItsPrice) {
    OrderBook b;
    b.submit(limit(1, Side::Sell, 10100, 10));
    auto r = b.submit(limit(2, Side::Buy, 10000, 10));
    EXPECT_TRUE(r.trades.empty());
    EXPECT_TRUE(r.resting);
    EXPECT_EQ(b.best_bid(), 10000);
    EXPECT_EQ(b.best_ask(), 10100);
}

TEST(OrderBook, MarketOrderRemainderIsDroppedNotRested) {
    OrderBook b;
    b.submit(limit(1, Side::Sell, 10000, 10));
    auto r = b.submit(market(2, Side::Buy, 25));
    ASSERT_EQ(r.trades.size(), 1u);
    EXPECT_EQ(r.remaining, 15);
    EXPECT_FALSE(r.resting);
    EXPECT_EQ(b.order_count(), 0u);
}

TEST(OrderBook, MarketOrderOnEmptyBook) {
    OrderBook b;
    auto r = b.submit(market(1, Side::Sell, 10));
    EXPECT_EQ(r.status, Status::Accepted);
    EXPECT_TRUE(r.trades.empty());
    EXPECT_FALSE(r.resting);
}

TEST(OrderBook, CancelRemovesOrderAndEmptyLevel) {
    OrderBook b;
    b.submit(limit(1, Side::Buy, 10000, 10));
    b.submit(limit(2, Side::Buy, 10000, 20));
    auto c = b.cancel(1);
    EXPECT_EQ(c.status, Status::Canceled);
    EXPECT_EQ(c.remaining, 10);
    EXPECT_EQ(b.bids(5)[0].qty, 20);
    EXPECT_EQ(b.bids(5)[0].orders, 1u);
    b.cancel(2);
    EXPECT_FALSE(b.best_bid().has_value());
    EXPECT_EQ(b.order_count(), 0u);
}

TEST(OrderBook, CancelAfterPartialFillReturnsRemaining) {
    OrderBook b;
    b.submit(limit(1, Side::Sell, 10000, 100));
    b.submit(market(2, Side::Buy, 40));
    auto c = b.cancel(1);
    EXPECT_EQ(c.status, Status::Canceled);
    EXPECT_EQ(c.remaining, 60);
}

TEST(OrderBook, CancelUnknownOrFilledOrder) {
    OrderBook b;
    EXPECT_EQ(b.cancel(99).status, Status::NotFound);
    b.submit(limit(1, Side::Sell, 10000, 10));
    b.submit(market(2, Side::Buy, 10));
    EXPECT_EQ(b.cancel(1).status, Status::NotFound);  // already fully filled
}

TEST(OrderBook, CanceledOrderIsNeverMatched) {
    OrderBook b;
    b.submit(limit(1, Side::Sell, 10000, 10));
    b.submit(limit(2, Side::Sell, 10000, 10));
    b.cancel(1);
    auto r = b.submit(market(3, Side::Buy, 10));
    ASSERT_EQ(r.trades.size(), 1u);
    EXPECT_EQ(r.trades[0].sell_id, 2u);
}

TEST(OrderBook, RejectsBadOrders) {
    OrderBook b;
    EXPECT_EQ(b.submit(limit(1, Side::Buy, 10000, 0)).status, Status::Rejected);
    EXPECT_EQ(b.submit(limit(2, Side::Buy, 0, 10)).status, Status::Rejected);
    EXPECT_EQ(b.submit(limit(3, Side::Buy, -5, 10)).status, Status::Rejected);
    b.submit(limit(4, Side::Buy, 10000, 10));
    auto dup = b.submit(limit(4, Side::Buy, 10000, 10));
    EXPECT_EQ(dup.status, Status::Rejected);
    EXPECT_EQ(dup.reason, "duplicate order id");
    EXPECT_EQ(b.order_count(), 1u);
}

TEST(OrderBook, DepthAggregatesLevels) {
    OrderBook b;
    b.submit(limit(1, Side::Buy, 10000, 10));
    b.submit(limit(2, Side::Buy, 10000, 15));
    b.submit(limit(3, Side::Buy, 9900, 5));
    b.submit(limit(4, Side::Sell, 10100, 7));
    auto bids = b.bids(10);
    ASSERT_EQ(bids.size(), 2u);
    EXPECT_EQ(bids[0].price, 10000);
    EXPECT_EQ(bids[0].qty, 25);
    EXPECT_EQ(bids[0].orders, 2u);
    EXPECT_EQ(bids[1].price, 9900);
    EXPECT_EQ(b.bids(1).size(), 1u);
    EXPECT_EQ(b.asks(10)[0].qty, 7);
}

TEST(OrderBook, SharesAreConserved) {
    // random-ish flow, then check that bought == sold and nothing went negative
    OrderBook b;
    uint64_t id = 1;
    int64_t bought = 0, sold = 0;
    uint32_t x = 12345;
    auto rnd = [&]() { x = x * 1664525u + 1013904223u; return x >> 8; };
    for (int i = 0; i < 5000; i++) {
        Side s = (rnd() & 1) ? Side::Buy : Side::Sell;
        int64_t price = 9900 + static_cast<int64_t>(rnd() % 21) * 10;
        int64_t qty = 1 + rnd() % 50;
        auto r = (rnd() % 10 == 0) ? b.submit(market(id++, s, qty)) : b.submit(limit(id++, s, price, qty));
        for (auto& t : r.trades) {
            EXPECT_GT(t.qty, 0);
            bought += t.qty;
            sold += t.qty;
        }
        if (b.best_bid() && b.best_ask()) {
            EXPECT_LT(*b.best_bid(), *b.best_ask());  // book is never crossed
        }
    }
    EXPECT_EQ(bought, sold);
    EXPECT_GT(bought, 0);
}
