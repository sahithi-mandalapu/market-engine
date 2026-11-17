// tiny TCP server: one thread per connection, each line goes through handle_line().
// Fine for a few dozen clients. If I needed thousands I'd switch to epoll.

#include <arpa/inet.h>
#include <netinet/in.h>
#include <signal.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <thread>

#include "me/engine.hpp"
#include "me/protocol.hpp"

static me::Engine g_engine;
static std::atomic<bool> g_running{true};

static void serve(int fd) {
    std::string buf;
    char chunk[4096];
    while (true) {
        ssize_t n = ::recv(fd, chunk, sizeof(chunk), 0);
        if (n <= 0) break;
        buf.append(chunk, static_cast<size_t>(n));
        if (buf.size() > 1 << 20) break;  // someone is sending garbage without newlines

        size_t pos;
        while ((pos = buf.find('\n')) != std::string::npos) {
            std::string line = buf.substr(0, pos);
            buf.erase(0, pos + 1);
            if (!line.empty() && line.back() == '\r') line.pop_back();

            std::string reply;
            for (const auto& l : me::handle_line(g_engine, line)) reply += l + "\n";
            const char* p = reply.data();
            size_t left = reply.size();
            while (left > 0) {
                ssize_t w = ::send(fd, p, left, MSG_NOSIGNAL);
                if (w <= 0) {
                    ::close(fd);
                    return;
                }
                p += w;
                left -= static_cast<size_t>(w);
            }
        }
    }
    ::close(fd);
}

int main(int argc, char** argv) {
    int port = 9099;
    if (argc > 1) port = std::atoi(argv[1]);
    if (const char* env = std::getenv("ME_PORT")) port = std::atoi(env);

    signal(SIGPIPE, SIG_IGN);

    int srv = ::socket(AF_INET, SOCK_STREAM, 0);
    int yes = 1;
    ::setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(static_cast<uint16_t>(port));
    if (::bind(srv, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        std::perror("bind");
        return 1;
    }
    ::listen(srv, 64);
    std::cout << "matching engine listening on :" << port << std::endl;

    while (g_running) {
        int fd = ::accept(srv, nullptr, nullptr);
        if (fd < 0) continue;
        std::thread(serve, fd).detach();
    }
    return 0;
}
