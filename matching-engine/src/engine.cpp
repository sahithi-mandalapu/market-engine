#include "me/engine.hpp"

namespace me {

Engine::Entry* Engine::find(const std::string& symbol) {
    std::shared_lock lk(map_mu_);
    auto it = books_.find(symbol);
    return it == books_.end() ? nullptr : it->second.get();
}

Engine::Entry* Engine::get_or_create(const std::string& symbol) {
    if (Entry* e = find(symbol)) return e;
    std::unique_lock lk(map_mu_);
    auto& slot = books_[symbol];  // somebody else may have created it between the two locks, that's fine
    if (!slot) slot = std::make_unique<Entry>();
    return slot.get();
}

Result Engine::submit(const std::string& symbol, Order o) {
    Entry* e = get_or_create(symbol);
    std::lock_guard lk(e->mu);
    return e->book.submit(o);
}

Result Engine::cancel(const std::string& symbol, uint64_t id) {
    Entry* e = find(symbol);
    if (!e) {
        Result r;
        r.status = Status::NotFound;
        return r;
    }
    std::lock_guard lk(e->mu);
    return e->book.cancel(id);
}

BookSnapshot Engine::snapshot(const std::string& symbol, size_t depth) {
    BookSnapshot s;
    Entry* e = find(symbol);
    if (!e) return s;
    std::lock_guard lk(e->mu);
    s.bids = e->book.bids(depth);
    s.asks = e->book.asks(depth);
    return s;
}

size_t Engine::symbol_count() const {
    std::shared_lock lk(map_mu_);
    return books_.size();
}

}  // namespace me
