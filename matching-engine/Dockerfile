FROM gcc:13 AS build
RUN apt-get update && apt-get install -y --no-install-recommends cmake && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
RUN cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DME_BUILD_TESTS=OFF && cmake --build build -j2 --target me_server

FROM debian:bookworm-slim
COPY --from=build /src/build/me_server /usr/local/bin/me_server
EXPOSE 9099
CMD ["me_server", "9099"]
