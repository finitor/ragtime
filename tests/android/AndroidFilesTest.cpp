// Two Android adapters, each on real sockets to its own fake data2g-host:
// a session file offered, accepted, saved and checked; one declined; a
// broadcast file received and checked; the Files rows kept across a restart;
// and a path outside the station's own folder refused.
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include "AndroidClient.h"
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

std::string contents(const std::string& path)
{
    std::ifstream in(path, std::ios::binary);
    return std::string((std::istreambuf_iterator<char>(in)), {});
}

std::string bulletin(size_t size, unsigned seed)
{
    std::string out;
    for (size_t i = 0; i < size; i++) out += (char)((i * 131 + seed * 17 + (i >> 5)) & 0xff);
    return out;
}
} // namespace

int main()
{
    const auto base = std::filesystem::temp_directory_path() /
        ("ragtime-android-files-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    const std::string folderA = (base / "a").string(), folderB = (base / "b").string();
    FakeAir air;
    FakeHost hostA(air), hostB(air);
    for (FakeHost* h : {&hostA, &hostB}) h->setIdleBroadcastMs(1);
    {
        Phone a("TESTA", folderA, hostA.kissPort(), hostA.commandPort()), b("TESTB", folderB, hostB.kissPort(), hostB.commandPort());
        std::vector<Phone*> both = {&a, &b};
        CHECK(run(air, both, [&] { return a.has("ready", "true") && b.has("ready", "true"); }));

        // Only a file under the station's own folder is ever sent.
        const auto outside = base / "outside.txt";
        std::ofstream(outside) << "not imported";
        bool refused = false;
        try { a.client.action("file-broadcast", outside.string(), now); }
        catch (const std::invalid_argument&) { refused = true; }
        CHECK(refused);
        refused = false;
        try { a.client.action("file-broadcast", folderA + "/outgoing/../../outside.txt", now); }
        catch (const std::invalid_argument&) { refused = true; }
        CHECK(refused);

        // No file to a station we are not in a ragchew with.
        const std::string note = bulletin(3000, 1);
        const std::string notePath = a.import("note.bin", note);
        refused = false;
        try { a.client.action("file-offer", std::string("TESTB") + '\0' + notePath, now); }
        catch (const std::runtime_error&) { refused = true; }
        CHECK(refused);

        CHECK(a.client.action("call", "TESTB", now));
        CHECK(run(air, both, [&] { return a.has("phase", "2") && b.has("phase", "2"); }));
        // Who called whom, for the phone's alert that a station has called.
        CHECK(a.has("incoming", "false") && b.has("incoming", "true"));
        CHECK(b.client.snapshot(now).find("\"past\":true") == std::string::npos);

        // A session file: offered, waiting for Receive, saved and checked.
        CHECK(a.client.action("file-offer", std::string("TESTB") + '\0' + notePath, now));
        CHECK(run(air, both, [&] { return b.row("note.bin", "s").flag("receive"); }));
        CHECK_EQ(b.row("note.bin", "s").text("via"), std::string("Session"));
        CHECK_EQ(b.row("note.bin", "s").text("peer"), std::string("TESTA"));
        CHECK(b.client.action("file-receive", b.row("note.bin", "s").text("key"), now));
        CHECK(run(air, both, [&] {
            return b.row("note.bin", "h").text("state") == "Saved" && a.row("note.bin", "h").text("state") == "Sent";
        }));
        const Row saved = b.row("note.bin", "h");
        CHECK(saved.flag("checked"));
        CHECK(!saved.flag("ours"));
        CHECK(saved.text("path").find(folderB + "/received/") == 0);
        CHECK(contents(saved.text("path")) == note);
        CHECK(a.row("note.bin", "h").flag("checked"));
        CHECK(a.row("note.bin", "h").flag("ours"));
        // Its line is in the ragchew on both sides.
        CHECK(a.client.snapshot(now).find("note.bin: Sent") != std::string::npos);
        CHECK(b.client.snapshot(now).find("note.bin: Saved") != std::string::npos);

        // One declined: both ends say so, and nothing is saved.
        const std::string second = a.import("second.bin", bulletin(900, 2));
        CHECK(a.client.action("file-offer", std::string("TESTB") + '\0' + second, now));
        CHECK(run(air, both, [&] { return b.row("second.bin", "s").flag("decline"); }));
        CHECK(b.client.action("file-decline", b.row("second.bin", "s").text("key"), now));
        CHECK(run(air, both, [&] { return !a.row("second.bin", "h").json.empty() && !b.row("second.bin", "h").json.empty(); }));
        CHECK(a.row("second.bin", "h").text("state").find("Declined") == 0);
        CHECK(a.row("second.bin", "h").text("path").empty());
        CHECK(b.row("second.bin", "h").text("path").empty());

        CHECK(a.client.action("leave", "", now));
        CHECK(run(air, both, [&] { return a.has("active", "false") && b.has("active", "false"); }));

        // A broadcast file: announced, waiting for Receive, whole and checked.
        const std::string news = bulletin(2500, 3);
        CHECK(a.client.action("file-broadcast", a.import("news.bin", news), now));
        CHECK(run(air, both, [&] { return b.row("news.bin", "b").flag("receive"); }));
        CHECK_EQ(b.row("news.bin", "b").text("via"), std::string("Broadcast"));
        CHECK(b.client.action("file-receive", b.row("news.bin", "b").text("key"), now));
        CHECK(run(air, both, [&] { return b.row("news.bin", "bh").flag("checked"); }));
        CHECK(contents(folderB + "/received/news.bin") == news);
        // Its card is in Broadcast on both sides.
        CHECK(a.client.snapshot(now).find("news.bin") != std::string::npos);
        CHECK(b.client.snapshot(now).find("\"state\":\"Saved\"") != std::string::npos);

        // The next ragchew reads the file's line from the session log: once.
        CHECK(a.client.action("call", "TESTB", now));
        CHECK(run(air, both, [&] { return a.has("phase", "2") && b.has("phase", "2"); }));
        const std::string again = a.client.snapshot(now);
        // What was said last time is marked as past, so nothing alerts for it.
        CHECK(again.find("\"past\":true") != std::string::npos);
        const auto line = again.find("note.bin: Sent");
        CHECK(line != std::string::npos);
        CHECK(again.find("note.bin: Sent", line + 1) == std::string::npos);
        CHECK(a.client.action("abort", "", now));
        CHECK(run(air, both, [&] { return a.has("active", "false") && b.has("active", "false"); }));
    }
    {
        // The Files rows are there again after the process is gone.
        Phone b("TESTB", folderB, hostB.kissPort(), hostB.commandPort());
        const Row saved = b.row("note.bin", "h");
        CHECK_EQ(saved.text("state"), std::string("Saved"));
        CHECK(saved.flag("checked"));
        CHECK(std::filesystem::exists(saved.text("path")));
        CHECK(b.row("second.bin", "h").text("state").find("Declined") == 0);
        // Forget takes the row away.
        CHECK(b.client.action("file-forget", b.row("second.bin", "h").text("key"), now));
        CHECK(b.row("second.bin", "h").json.empty());
    }
    std::filesystem::remove_all(base);
    return ragtime_test::finish();
}
