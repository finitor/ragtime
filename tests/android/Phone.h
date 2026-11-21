// The Android app's connection worker without Android, for the adapter's
// tests: three sockets to a fake data2g-host and AndroidClient's byte-in,
// byte-out calls, with the snapshot's "files" rows picked apart.
#ifndef RAGTIME_TESTS_ANDROID_PHONE_H
#define RAGTIME_TESTS_ANDROID_PHONE_H

#include <cstdint>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include "AndroidClient.h"
#include "Check.h"

namespace ragtime_test
{
inline uint64_t now = 1792627200000; // the tests move it 10 ms a step

inline int connectTo(int port)
{
    int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons((uint16_t)port);
    if (fd >= 0 && ::connect(fd, (sockaddr*)&addr, sizeof(addr)) != 0)
    {
        ::close(fd);
        fd = -1;
    }
    return fd;
}

// One row of the snapshot's "files" array. The test's names and paths hold
// no braces, so a row ends at its first '}'.
struct Row
{
    std::string json;
    std::string text(const std::string& key) const
    {
        const std::string mark = "\"" + key + "\":\"";
        auto at = json.find(mark);
        if (at == std::string::npos) return {};
        at += mark.size();
        return json.substr(at, json.find('"', at) - at);
    }
    bool flag(const std::string& key) const { return json.find("\"" + key + "\":true") != std::string::npos; }
};

// The Android app's connection worker, without Android: three sockets and
// the adapter's byte-in, byte-out calls.
struct Phone
{
    // The host's KISS and command ports; session data is the command port + 1.
    Phone(const std::string& call, const std::string& directory, int kissPort, int commandPort)
        : client(call, "CN87", directory, now), folder(directory)
    {
        fds[0] = connectTo(kissPort);
        fds[1] = connectTo(commandPort);
        fds[2] = connectTo(commandPort + 1);
        CHECK(fds[0] >= 0 && fds[1] >= 0 && fds[2] >= 0);
        client.connected(now);
        client.dataPort(true, now);
    }
    ~Phone()
    {
        for (int fd : fds)
        {
            if (fd >= 0) ::close(fd);
        }
    }
    void pump()
    {
        for (int port = 0; port < 3; port++)
        {
            std::vector<uint8_t> in;
            uint8_t buffer[4096];
            for (;;)
            {
                ssize_t n = ::recv(fds[port], buffer, sizeof(buffer), MSG_DONTWAIT);
                if (n <= 0) break;
                in.insert(in.end(), buffer, buffer + n);
            }
            if (!in.empty()) client.receive(port, in, now);
        }
        client.tick(now);
        for (int port = 0; port < 3; port++)
        {
            const std::vector<uint8_t> out = client.output(port, now);
            const uint8_t* at = out.data();
            size_t left = out.size();
            while (left > 0)
            {
                ssize_t n = ::send(fds[port], at, left, MSG_NOSIGNAL);
                if (n <= 0) break;
                at += n;
                left -= (size_t)n;
            }
        }
    }
    std::vector<Row> rows() const
    {
        const std::string all = client.snapshot(now);
        std::vector<Row> out;
        auto at = all.find("\"files\":[");
        if (at == std::string::npos) return out;
        for (at = all.find('{', at); at != std::string::npos; at = all.find('{', at))
        {
            const auto end = all.find('}', at);
            out.push_back({all.substr(at, end - at + 1)});
            at = end;
        }
        return out;
    }
    // The first row named so whose key starts with one of these letters.
    Row row(const std::string& name, const std::string& kinds) const
    {
        for (const Row& r : rows())
        {
            const std::string key = r.text("key");
            if (r.text("name") == name && !key.empty() && kinds.find(key[0]) != std::string::npos) return r;
        }
        return {};
    }
    bool has(const std::string& field, const std::string& value) const
    {
        return client.snapshot(now).find("\"" + field + "\":" + value) != std::string::npos;
    }
    // A file as Documents.import leaves one: under the station's own folder.
    std::string import(const std::string& name, const std::string& bytes) const
    {
        const auto path = std::filesystem::path(folder) / "outgoing" / name;
        std::filesystem::create_directories(path.parent_path());
        std::ofstream(path, std::ios::binary) << bytes;
        return path.string();
    }

    ragtime::android::AndroidClient client;
    std::string folder;
    int fds[3] = {-1, -1, -1};
};
} // namespace ragtime_test

#endif
