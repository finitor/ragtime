#ifndef RAGTIME_ANDROID_DEMO_AIR_H
#define RAGTIME_ANDROID_DEMO_AIR_H

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <thread>

namespace ragtime::android
{
// Demo mode's net: the bench's fake data2g-host (tests/FakeHost.h) for the
// operator, on loopback ports of its own, and a few made-up stations on
// fake hosts beside it, each a real HostLink, BroadcastChat and SessionChat.
// They check in, chat, answer what the operator says, take a call, ragchew,
// offer a bulletin and save what they are sent. The app connects to it as
// it would to data2g-host, so nothing on the screen is faked; and nothing
// here is Data2G's or goes near a modem or a radio.
class DemoAir
{
public:
    struct Options
    {
        // A burst keys the pretend transmitter this long, so TX shows.
        int airtimeMs = 1500;
        // Session bytes a second, so a file takes a moment (0: at once).
        int sessionRate = 400;
        uint32_t seed = 12345;
    };

    // folder: where the stations keep their bulletins and what they are
    // sent. Throws when no loopback ports are to be had.
    DemoAir(const std::string& folder, const std::string& operatorCall, uint64_t nowMs, const Options& options);
    DemoAir(const std::string& folder, const std::string& operatorCall, uint64_t nowMs);
    ~DemoAir();
    DemoAir(const DemoAir&) = delete;
    DemoAir& operator=(const DemoAir&) = delete;

    // The operator's host: session data is the command port + 1.
    int kissPort() const;
    int commandPort() const;

    // One turn: what has come in on every socket (waiting up to waitMs for
    // something), then each station's.
    void step(uint64_t nowMs, int waitMs);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// A DemoAir stepped on a thread of its own, for the app.
class DemoRunner
{
public:
    DemoRunner(const std::string& folder, const std::string& operatorCall);
    ~DemoRunner();
    DemoRunner(const DemoRunner&) = delete;
    DemoRunner& operator=(const DemoRunner&) = delete;

    int kissPort() const { return air_.kissPort(); }
    int commandPort() const { return air_.commandPort(); }

private:
    DemoAir air_;
    std::atomic<bool> stopping_{false};
    std::thread thread_;
};
} // namespace ragtime::android
#endif
