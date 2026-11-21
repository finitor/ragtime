// The Android adapter's advertised capabilities, UTF-8 snapshot and durable
// broadcast numbering. Socket/JNI/UI interop is tested on-device in android/.
#include <chrono>
#include <filesystem>
#include <fstream>
#include <string>

#include "Check.h"
#include "AndroidClient.h"

using namespace ragtime;
namespace
{
constexpr uint64_t now = 1792627200000;
void say(android::AndroidClient& client, const std::string& text)
{
    // Deliberately split command lines across the adapter boundary.
    for (char c : text) client.receive(1, {(uint8_t)c}, now);
}
void open(android::AndroidClient& client)
{
    client.connected(now);
    auto commands = client.output(1, now);
    CHECK(std::string(commands.begin(), commands.end()).find("MYCALL TESTA") != std::string::npos);
    say(client, "OK\rOK\rWRONG\rOK\rOK\rBCAST PORT 2\rMODE qpsk-r1/3 2300 32 64 1.50 20.00\rOK\r");
    client.output(1, now);
    client.dataPort(true, now);
    CHECK(client.link().groupOpen());
}
}
int main()
{
    const auto directory = std::filesystem::temp_directory_path() /
        ("ragtime-android-test-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    const std::string text = "CQ π 🚀 \"quote\" \\ slash\nsecond line";
    {
        android::AndroidClient client("TESTA", "CN87", directory.string(), now);
        open(client);
        CHECK(client.action("broadcast", text, now));
        auto wire = client.output(0, now);
        link::KissDecoder decoder;
        std::vector<link::KissFrame> frames;
        decoder.feed(wire.data(), wire.size(), frames);
        bool hello = false;
        bool heardText = false;
        for (const auto& frame : frames)
        {
            if (frame.command != link::KISS_ACKMODE || frame.payload.size() < 2) continue;
            std::vector<protocol::Record> records;
            auto bytes = std::vector<uint8_t>(frame.payload.begin() + 2, frame.payload.end());
            protocol::decodeFrame(bytes, records);
            for (const auto& record : records)
            {
                if (auto h = record.as<protocol::Hello>())
                {
                    hello = true;
                    CHECK(h->takes & protocol::TAKES_SESSION_FILES);
                    CHECK(h->takes & protocol::TAKES_BROADCAST_FILES);
                    CHECK(h->takes & protocol::TAKES_TEXT);
                }
                if (auto t = record.as<protocol::Text>()) heardText = t->text == text;
            }
        }
        CHECK(hello);
        CHECK(heardText);
        CHECK(client.snapshot(now).find("π 🚀") != std::string::npos);
        CHECK(client.snapshot(now).find("\\\"quote\\\"") != std::string::npos);
        CHECK(client.action("call", "TESTB", now));
        say(client, "OK\rCONNECTED TESTA TESTB 2300\r");
        auto data = client.output(2, now);
        protocol::StreamReader reader;
        std::vector<protocol::Record> records;
        CHECK(reader.feed(data, records));
        CHECK(reader.greeted());
        CHECK_EQ(reader.hello().takes, protocol::TAKES_TEXT | protocol::TAKES_STATUS | protocol::TAKES_SESSION_FILES);
    }
    {
        android::AndroidClient client("TESTA", "CN87", directory.string(), now + 1000);
        CHECK(client.snapshot(now).find("π 🚀") != std::string::npos);
        open(client);
        CHECK(client.action("broadcast", "after restart", now + 1000));
        uint32_t number = 0;
        std::ifstream(directory / "number") >> number;
        CHECK_EQ(number, 2U);
    }
    std::filesystem::remove_all(directory);
    return ragtime_test::finish();
}
