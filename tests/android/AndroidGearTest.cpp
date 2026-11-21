// The gearshift through the Android adapter, on fake hosts that give
// thresholds with MODES as data2g-host does since Data2G PR #186: a ladder
// of gears, Auto following the weakest station heard, a gear chosen by
// hand and kept across a restart, bursts sent in the gear's mode, and a
// host with no thresholds, which has no gears and still sends.
#include <algorithm>
#include <chrono>
#include <filesystem>
#include <string>
#include <vector>

#include "Check.h"
#include "FakeHost.h"
#include "Phone.h"

using namespace ragtime;
using namespace ragtime_test;

namespace
{
template <typename Done>
bool run(FakeAir& air, std::vector<Phone*> phones, Done done, int steps = 6000)
{
    for (int i = 0; i < steps; i++)
    {
        if (done()) return true;
        air.step(1);
        for (Phone* p : phones) p->pump();
        now += 10;
    }
    return done();
}

// The snapshot's "gear" object.
std::string gear(const Phone& phone)
{
    const std::string all = phone.client.snapshot(now);
    const auto at = all.find("\"gear\":{");
    return at == std::string::npos ? std::string() : all.substr(at + 7);
}
// A top-level text field of it (before the ladder).
std::string field(const std::string& g, const std::string& key)
{
    const std::string mark = "\"" + key + "\":\"";
    const auto at = g.find(mark);
    if (at == std::string::npos || at > g.find("\"ladder\":[")) return {};
    return g.substr(at + mark.size(), g.find('"', at + mark.size()) - at - mark.size());
}
// The ladder's modes, most robust first.
std::vector<std::string> ladder(const std::string& g)
{
    std::vector<std::string> modes;
    const std::string mark = "{\"mode\":\"";
    for (auto at = g.find(mark, g.find("\"ladder\":[")); at != std::string::npos; at = g.find(mark, at + 1))
        modes.push_back(g.substr(at + mark.size(), g.find('"', at + mark.size()) - at - mark.size()));
    return modes;
}
bool told(const FakeHost& host, const std::string& command)
{
    const auto& all = host.commands();
    return std::find(all.begin(), all.end(), command) != all.end();
}
} // namespace

int main()
{
    const auto base = std::filesystem::temp_directory_path() /
        ("ragtime-android-gear-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    FakeAir air;
    FakeHost hostA(air), hostB(air), hostOld(air);
    for (FakeHost* h : {&hostA, &hostB, &hostOld})
    {
        h->setIdleBroadcastMs(1);
        h->setReportsSnr(true);
    }
    hostA.setThresholds(true);
    hostB.setThresholds(true);
    hostB.setSignalDb(18.0);
    std::string byHand;
    {
        Phone a("TESTA", (base / "a").string(), hostA.kissPort(), hostA.commandPort());
        Phone b("TESTB", (base / "b").string(), hostB.kissPort(), hostB.commandPort());
        std::vector<Phone*> both = {&a, &b};
        CHECK(run(air, both, [&] { return a.has("ready", "true") && b.has("ready", "true") && !ladder(gear(a)).empty(); }));

        // The host's thresholds make a ladder. With nobody heard yet Auto has
        // nothing to go by: no gear is in effect, and a line goes in the mode
        // the modem is set to, with no mode asked of it.
        std::string g = gear(a);
        const std::vector<std::string> gears = ladder(g);
        CHECK(gears.size() >= 3);
        CHECK(g.find("\"hostThresholds\":true") != std::string::npos);
        CHECK(g.find("\"auto\":true") != std::string::npos);
        CHECK(g.find("\"waiting\":true") != std::string::npos);
        CHECK(field(g, "mode").empty() && field(g, "label").empty());
        CHECK(field(g, "snr").empty());
        CHECK_EQ(field(g, "pathLabel"), std::string("Calm"));
        CHECK(a.client.action("broadcast", "first line, nobody heard yet", now));
        CHECK(run(air, both, [&] { return b.client.snapshot(now).find("nobody heard yet") != std::string::npos; }));
        for (const std::string& command : hostA.commands()) CHECK(command.compare(0, 10, "BCAST MODE") != 0);

        // A strong station heard: Auto shifts up, and says whose S/N it goes by.
        CHECK(b.client.action("broadcast", "CQ D2G de TESTB", now));
        CHECK(run(air, both, [&] { return field(gear(a), "snrFrom") == "TESTB"; }));
        g = gear(a);
        CHECK(!field(g, "snr").empty());
        CHECK(g.find("\"waiting\":false") != std::string::npos);
        CHECK(!field(g, "label").empty() && !field(g, "speed").empty());
        const std::string fast = field(g, "mode");
        CHECK(fast != gears.front());
        // What we send goes in that gear's mode.
        CHECK(a.client.action("broadcast", "TESTB de TESTA, fine copy", now));
        CHECK(run(air, both, [&] { return told(hostA, "BCAST MODE 2 " + fast); }));
        CHECK(run(air, both, [&] { return b.client.snapshot(now).find("fine copy") != std::string::npos; }));

        // The same station gone weak: Auto comes back down by itself.
        hostB.setSignalDb(-14.0);
        for (int i = 0; i < 6; i++)
        {
            CHECK(b.client.action("broadcast", "fading " + std::to_string(i), now));
            CHECK(run(air, both, [&] { return a.client.snapshot(now).find("fading " + std::to_string(i)) != std::string::npos; }));
            // Bursts some seconds apart, as on the air: the same S/N again
            // within 5 s is taken for the same burst.
            run(air, both, [] { return false; }, 600);
        }
        CHECK_EQ(field(gear(a), "mode"), gears.front());
        // The faster gears say how many dB short the path is for them.
        CHECK(gear(a).find("\"short\":\"+") != std::string::npos);

        // A gear chosen by hand turns Auto off and is what goes on the air.
        byHand = gears[1];
        CHECK(a.client.action("gear", byHand, now));
        g = gear(a);
        CHECK(g.find("\"auto\":false") != std::string::npos);
        CHECK_EQ(field(g, "mode"), byHand);
        CHECK(a.client.action("broadcast", "in second gear", now));
        CHECK(run(air, both, [&] { return told(hostA, "BCAST MODE 2 " + byHand); }));
        // Not a gear: refused, and nothing changes.
        CHECK(!a.client.action("gear", "no-such-mode", now));
        CHECK_EQ(field(gear(a), "mode"), byHand);

        // A worse path wants more S/N of every gear.
        CHECK(a.client.action("gear-path", "mpp", now));
        CHECK_EQ(field(gear(a), "pathLabel"), std::string("Poor"));
        CHECK(!a.client.action("gear-path", "nonsense", now));
        CHECK(a.client.action("gear-path", "awgn", now));
    }
    {
        // The gear chosen by hand is still chosen after a restart.
        Phone a("TESTA", (base / "a").string(), hostA.kissPort(), hostA.commandPort());
        std::vector<Phone*> one = {&a};
        CHECK(run(air, one, [&] { return a.has("ready", "true") && !ladder(gear(a)).empty(); }));
        CHECK(gear(a).find("\"auto\":false") != std::string::npos);
        CHECK_EQ(field(gear(a), "mode"), byHand);
        CHECK(a.client.action("gear-auto", "true", now));
        CHECK(gear(a).find("\"auto\":true") != std::string::npos);
    }
    {
        // A host from before thresholds: no gears, and it sends all the same.
        Phone old("TESTC", (base / "c").string(), hostOld.kissPort(), hostOld.commandPort());
        std::vector<Phone*> one = {&old};
        CHECK(run(air, one, [&] { return old.has("ready", "true"); }));
        CHECK(ladder(gear(old)).empty());
        CHECK(gear(old).find("\"hostThresholds\":false") != std::string::npos);
        CHECK(field(gear(old), "mode").empty());
        CHECK(old.client.action("broadcast", "no gears here", now));
        CHECK(run(air, one, [&] { return old.client.snapshot(now).find("\"state\":\"Sent\"") != std::string::npos; }));
        CHECK(!old.client.action("gear", "qpsk-r1/3", now));
    }
    std::filesystem::remove_all(base);
    return ragtime_test::finish();
}
