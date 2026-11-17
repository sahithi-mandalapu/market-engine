#include <gtest/gtest.h>

#include "me/protocol.hpp"

using namespace me;

TEST(Price, ParseAndFormat) {
    EXPECT_EQ(parse_price("189.25"), 18925);
    EXPECT_EQ(parse_price("189.2"), 18920);
    EXPECT_EQ(parse_price("189"), 18900);
    EXPECT_EQ(parse_price("0"), 0);
    EXPECT_EQ(parse_price("0.05"), 5);
    EXPECT_FALSE(parse_price("189.255").has_value());
    EXPECT_FALSE(parse_price("abc").has_value());
    EXPECT_FALSE(parse_price("1.2.3").has_value());
    EXPECT_FALSE(parse_price("-5").has_value());
    EXPECT_FALSE(parse_price("").has_value());
    EXPECT_FALSE(parse_price(".").has_value());
    EXPECT_EQ(format_price(18925), "189.25");
    EXPECT_EQ(format_price(18905), "189.05");
    EXPECT_EQ(format_price(5), "0.05");
}

TEST(Protocol, NewOrderRestsThenFills) {
    Engine e;
    auto a = handle_line(e, "NEW 1 aapl SELL LIMIT 100 190.50");
    EXPECT_EQ(a, (std::vector<std::string>{"ACK 1 RESTING 100", "OK"}));

    auto b = handle_line(e, "NEW 2 AAPL BUY MARKET 40 0");
    EXPECT_EQ(b, (std::vector<std::string>{"TRADE 2 1 AAPL 190.50 40", "ACK 2 DONE 0", "OK"}));

    auto book = handle_line(e, "BOOK AAPL");
    EXPECT_EQ(book, (std::vector<std::string>{"LEVEL ASK 190.50 60 1", "OK"}));
}

TEST(Protocol, Cancel) {
    Engine e;
    handle_line(e, "NEW 1 MSFT BUY LIMIT 10 400.00");
    EXPECT_EQ(handle_line(e, "CANCEL 1 MSFT"), (std::vector<std::string>{"CANCELED 1 10", "OK"}));
    EXPECT_EQ(handle_line(e, "CANCEL 1 MSFT"), (std::vector<std::string>{"NOTFOUND 1", "OK"}));
    EXPECT_EQ(handle_line(e, "CANCEL 1 NVDA"), (std::vector<std::string>{"NOTFOUND 1", "OK"}));
}

TEST(Protocol, BadInputIsRejectedNotCrashed) {
    Engine e;
    EXPECT_EQ(handle_line(e, "")[0], "ERR empty_command");
    EXPECT_EQ(handle_line(e, "HELLO")[0], "ERR unknown_command");
    EXPECT_EQ(handle_line(e, "NEW 1 AAPL BUY")[0], "ERR usage_NEW_id_symbol_side_type_qty_price");
    EXPECT_EQ(handle_line(e, "NEW x AAPL BUY LIMIT 1 1.00")[0], "ERR bad_id");
    EXPECT_EQ(handle_line(e, "NEW 0 AAPL BUY LIMIT 1 1.00")[0], "ERR bad_id");
    EXPECT_EQ(handle_line(e, "NEW 1 AAPL HOLD LIMIT 1 1.00")[0], "REJECT 1 bad_side");
    EXPECT_EQ(handle_line(e, "NEW 1 AAPL BUY STOP 1 1.00")[0], "REJECT 1 bad_type");
    EXPECT_EQ(handle_line(e, "NEW 1 AAPL BUY LIMIT -3 1.00")[0], "REJECT 1 bad_qty");
    EXPECT_EQ(handle_line(e, "NEW 1 AAPL BUY LIMIT 3 1.999")[0], "REJECT 1 bad_price");
    EXPECT_EQ(handle_line(e, "NEW 1 AAPL BUY LIMIT 0 1.00")[0], "REJECT 1 qty_must_be_positive");
    EXPECT_EQ(handle_line(e, "NEW 1 AAPL BUY LIMIT 5 0")[0], "REJECT 1 limit_price_must_be_positive");
    EXPECT_EQ(handle_line(e, "BOOK AAPL 0")[0], "ERR bad_depth");
}

TEST(Protocol, DuplicateIdRejected) {
    Engine e;
    handle_line(e, "NEW 7 AAPL BUY LIMIT 5 10.00");
    EXPECT_EQ(handle_line(e, "NEW 7 AAPL BUY LIMIT 5 10.00")[0], "REJECT 7 duplicate_order_id");
}

TEST(Protocol, PingAndEmptyBook) {
    Engine e;
    EXPECT_EQ(handle_line(e, "PING"), (std::vector<std::string>{"PONG", "OK"}));
    EXPECT_EQ(handle_line(e, "BOOK NOPE"), (std::vector<std::string>{"OK"}));
}
