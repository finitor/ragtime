// Demo mode's net with an Android adapter on it, as the app connects one:
// the stations check in with their locators, answer a line said to
// everyone and one addressed to them, take a call and ragchew, offer their
// bulletin when asked, save a file sent to them and leave after 73; and a
// bulletin file comes on D2G for the Files tab to take.
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>

#include "Check.h"
#include "DemoAir.h"
#include "Phone.h"

using namespace ragtime;
using namespace ragtime_test;

namespace
{
template <typename Done>
bool run(android::DemoAir& air, Phone& phone, Done done, int steps = 9000)
{
    for (int i = 0; i < steps; i++)
    {
        if (done()) return true;
        air.step(now, 1);
        phone.pump();
        now += 10;
    }
    return done();
}

std::string contents(const std::string& path)
{
    std::ifstream in(path, std::ios::binary);
    return std::string((std::istreambuf_iterator<char>(in)), {});
}

size_t count(const std::string& all, const std::string& what)
{
    size_t n = 0;
    for (auto at = all.find(what); at != std::string::npos; at = all.find(what, at + 1)) n++;
    return n;
}
} // namespace

int main()
{
    const auto base = std::filesystem::temp_directory_path() /
        ("ragtime-android-demo-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    {
        android::DemoAir::Options quick;
        quick.airtimeMs = 0;
        quick.sessionRate = 0;
        android::DemoAir air((base / "air").string(), "demo", now, quick);
        CHECK(air.kissPort() > 0 && air.commandPort() > 0);
        Phone phone("DEMO", (base / "me").string(), air.kissPort(), air.commandPort());
        auto shot = [&] { return phone.client.snapshot(now); };

        // The net comes up: every station, with its locator's place.
        CHECK(run(air, phone, [&] {
            const std::string s = shot();
            return s.find("\"call\":\"W1QSO\"") != std::string::npos && s.find("\"call\":\"K7QTH\"") != std::string::npos
                   && s.find("\"call\":\"DL3QRM\"") != std::string::npos
                   && s.find("\"call\":\"F5QRN\"") != std::string::npos;
        }));
        CHECK(run(air, phone, [&] { return shot().find("FN42") != std::string::npos; }));

        // A line to everyone is answered by one station, to us.
        CHECK(phone.client.action("broadcast", "CQ D2G de DEMO, how copy?", now));
        CHECK(run(air, phone, [&] { return shot().find("good copy on you here in") != std::string::npos; }));
        CHECK_EQ(count(shot(), "good copy on you here in"), (size_t)1);

        // One addressed to a station is acknowledged, and answered by it.
        CHECK(phone.client.action("broadcast", "@W1QSO good evening from DEMO", now));
        CHECK(run(air, phone, [&] {
            const std::string s = shot();
            return s.find("\"state\":\"Acknowledged\"") != std::string::npos
                   && s.find("copy you fine, thanks. 73 de W1QSO") != std::string::npos;
        }));

        // A ragchew: the call is taken and the station says hello.
        CHECK(phone.client.action("call", "K7QTH", now));
        CHECK(run(air, phone, [&] { return phone.has("phase", "2"); }));
        CHECK(run(air, phone, [&] { return shot().find("Name here is Kay") != std::string::npos; }));
        CHECK(phone.client.action("session", "Good copy here. What's your rig?", now));
        CHECK(run(air, phone, [&] { return shot().find("Copy that, fine business.") != std::string::npos; }));

        // Asked for its bulletin, it offers the file; Receive saves it whole.
        CHECK(phone.client.action("session", "Can you send me your bulletin file?", now));
        CHECK(run(air, phone, [&] { return phone.row("K7QTH-bulletin.txt", "s").flag("receive"); }));
        CHECK(phone.client.action("file-receive", phone.row("K7QTH-bulletin.txt", "s").text("key"), now));
        CHECK(run(air, phone, [&] { return phone.row("K7QTH-bulletin.txt", "h").text("state") == "Saved"; }));
        CHECK(phone.row("K7QTH-bulletin.txt", "h").flag("checked"));
        CHECK(contents(phone.row("K7QTH-bulletin.txt", "h").text("path")).find("QST de K7QTH") == 0);

        // A file sent to it is saved there, and it says thanks.
        const std::string mine = phone.import("hello.txt", "Hello from the demo.\n");
        CHECK(phone.client.action("file-offer", std::string("K7QTH") + '\0' + mine, now));
        CHECK(run(air, phone, [&] { return phone.row("hello.txt", "h").text("state") == "Sent"; }));
        CHECK(run(air, phone, [&] { return shot().find("Got hello.txt") != std::string::npos; }));
        CHECK(contents((base / "air" / "K7QTH" / "hello.txt").string()) == "Hello from the demo.\n");

        // 73 and the station leaves.
        CHECK(phone.client.action("session", "Thanks for the ragchew, 73", now));
        CHECK(run(air, phone, [&] { return phone.has("active", "false"); }));
        CHECK(shot().find("thanks for the ragchew. QRT for now.") != std::string::npos);

        // W1QSO's bulletin on D2G waits for Receive, and comes whole.
        CHECK(run(air, phone, [&] { return phone.row("W1QSO-net-news-1.txt", "b").flag("receive"); }));
        CHECK(phone.client.action("file-receive", phone.row("W1QSO-net-news-1.txt", "b").text("key"), now));
        CHECK(run(air, phone, [&] { return phone.row("W1QSO-net-news-1.txt", "bh").flag("checked"); }, 20000));
        CHECK(contents((base / "me" / "received" / "W1QSO-net-news-1.txt").string()).find("QST de W1QSO") == 0);
    }
    {
        // An operator with a demo station's callsign has the others to talk to.
        android::DemoAir::Options quick;
        quick.airtimeMs = 0;
        android::DemoAir air((base / "air2").string(), "W1QSO", now, quick);
        Phone phone("W1QSO", (base / "me2").string(), air.kissPort(), air.commandPort());
        CHECK(run(air, phone, [&] { return phone.client.snapshot(now).find("\"call\":\"K7QTH\"") != std::string::npos; }));
        CHECK(phone.client.snapshot(now).find("\"call\":\"W1QSO\"") == std::string::npos);
    }
    {
        // On its own thread, as the app runs it: it starts, serves and stops.
        android::DemoRunner runner((base / "air3").string(), "DEMO");
        CHECK(runner.kissPort() > 0);
        int fd = connectTo(runner.commandPort());
        CHECK(fd >= 0);
        if (fd >= 0) ::close(fd);
    }
    std::filesystem::remove_all(base);
    return ragtime_test::finish();
}
