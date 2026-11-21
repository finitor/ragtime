// Inline pictures through the Android adapter: a picked picture's pixels
// shrunk and coded as a JPEG 2000 codestream that shows from any first
// part; one sent in a ragchew and one on D2G, each taken without asking
// and shown by the far end from its key while it comes and from its path
// after. Skipped in a build without the codecs (-DRAGTIME_PICTURES=OFF).
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "AndroidPictures.h"
#include "Check.h"
#include "FakeHost.h"
#include "Phone.h"
#include "ragtime/picture/Picture.h"

using namespace ragtime;
using namespace ragtime_test;

namespace
{
template <typename Done>
bool run(FakeAir& air, std::vector<Phone*> phones, Done done, int steps = 9000)
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

// A test card: a sky, a hill, and a mast with thin guy wires.
std::vector<uint8_t> card(int width, int height)
{
    std::vector<uint8_t> rgba((size_t)width * (size_t)height * 4);
    for (int y = 0; y < height; y++)
        for (int x = 0; x < width; x++)
        {
            uint8_t* p = rgba.data() + ((size_t)y * (size_t)width + (size_t)x) * 4;
            const bool hill = y > height * 2 / 3 + (x % 97) / 9;
            const bool mast = (x > width / 2 - 3 && x < width / 2 + 3 && y > height / 5) || (x + y) % 211 == 0;
            p[0] = (uint8_t)(mast ? 30 : hill ? 60 : 110 + y * 100 / height);
            p[1] = (uint8_t)(mast ? 30 : hill ? 120 : 150 + y * 60 / height);
            p[2] = (uint8_t)(mast ? 30 : hill ? 50 : 230);
            p[3] = 255;
        }
    return rgba;
}

int le32(const std::vector<uint8_t>& b, size_t at)
{
    return (int)(b[at] | (b[at + 1] << 8) | (b[at + 2] << 16) | ((uint32_t)b[at + 3] << 24));
}

std::vector<uint8_t> contents(const std::string& path)
{
    std::ifstream in(path, std::ios::binary);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)), {});
}

// The value of a text field in the first chat row that has all of these.
std::string field(const std::string& snapshot, const std::string& has, const std::string& also, const std::string& key)
{
    for (auto at = snapshot.find('{', 1); at != std::string::npos; at = snapshot.find('{', at + 1))
    {
        const auto end = snapshot.find('}', at);
        const std::string row = snapshot.substr(at, end - at + 1);
        if (row.find(has) == std::string::npos || row.find(also) == std::string::npos) continue;
        const std::string mark = "\"" + key + "\":\"";
        const auto from = row.find(mark);
        if (from == std::string::npos) continue;
        return row.substr(from + mark.size(), row.find('"', from + mark.size()) - from - mark.size());
    }
    return {};
}
} // namespace

int main()
{
    if (!android::picturesAvailable())
    {
        std::printf("built without the picture codecs: skipped\n");
        return ragtime_test::finish();
    }
    std::string error;
    const std::vector<uint8_t> pixels = card(900, 600);

    // Shrunk to 640 on its longer side and coded within the bytes asked.
    const std::vector<uint8_t> coded = android::encodePicture(pixels.data(), 900, 600, 640, 6000, error);
    CHECK(!coded.empty() && coded.size() <= 6000);
    CHECK(picture::isCodestream(coded.data(), coded.size()));
    const std::vector<uint8_t> shown = android::decodePicture(coded.data(), coded.size(), 2048, error);
    CHECK(shown.size() > 8);
    CHECK_EQ(le32(shown, 0), 640);
    CHECK(le32(shown, 4) == 426 || le32(shown, 4) == 427);
    CHECK_EQ(shown.size(), (size_t)8 + (size_t)le32(shown, 0) * (size_t)le32(shown, 4) * 4);
    CHECK_EQ((int)shown[8 + 3], 255);
    // The sky is still blue and the hill still green.
    CHECK(shown[8 + 4 * 20 + 2] > shown[8 + 4 * 20 + 0]);
    const size_t low = 8 + ((size_t)(le32(shown, 4) - 5) * 640 + 20) * 4;
    CHECK(shown[low + 1] > shown[low + 2]);
    // Any first part of it is the whole picture, softer.
    const std::vector<uint8_t> half = android::decodePicture(coded.data(), coded.size() / 2, 2048, error);
    CHECK(half.size() == shown.size());
    // A bigger budget is used, a tiny one refused, and what is no picture shows nothing.
    CHECK(android::encodePicture(pixels.data(), 900, 600, 640, 20000, error).size() > coded.size());
    CHECK(android::encodePicture(pixels.data(), 900, 600, 640, 40, error).empty());
    const std::vector<uint8_t> junk(500, 0x5a);
    CHECK(android::decodePicture(junk.data(), junk.size(), 2048, error).empty());
    // Bigger than the screen will take: not decoded at all.
    CHECK(android::decodePicture(coded.data(), coded.size(), 320, error).empty());

    const auto base = std::filesystem::temp_directory_path() /
        ("ragtime-android-pictures-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    const std::string folderA = (base / "a").string(), folderB = (base / "b").string();
    FakeAir air;
    FakeHost hostA(air), hostB(air);
    for (FakeHost* h : {&hostA, &hostB}) h->setIdleBroadcastMs(1);
    {
        Phone a("TESTA", folderA, hostA.kissPort(), hostA.commandPort()), b("TESTB", folderB, hostB.kissPort(), hostB.commandPort());
        std::vector<Phone*> both = {&a, &b};
        CHECK(run(air, both, [&] { return a.has("ready", "true") && b.has("ready", "true"); }));
        CHECK(a.client.action("call", "TESTB", now));
        CHECK(run(air, both, [&] { return a.has("phase", "2") && b.has("phase", "2"); }));

        // A picture in the ragchew: no Receive, it just comes.
        const std::string mast = a.import("mast.j2k", std::string(coded.begin(), coded.end()));
        CHECK(a.client.action("picture-offer", std::string("TESTB") + '\0' + mast, now));
        // Ours shows at once, from its key, whole.
        CHECK(a.row("mast.j2k", "s").flag("picture"));
        CHECK(a.client.pictureBytes(a.row("mast.j2k", "s").text("key"), now) == coded);
        CHECK(field(a.client.snapshot(now), "mast.j2k", "\"picture\":true", "file") == a.row("mast.j2k", "s").text("key"));
        CHECK(run(air, both, [&] { return b.row("mast.j2k", "h").text("state") == "Saved"; }));
        CHECK(b.row("mast.j2k", "h").flag("picture"));
        CHECK(!b.row("mast.j2k", "h").flag("receive"));
        // Its line in the ragchew names the saved picture, and it decodes.
        const std::string saved = field(b.client.snapshot(now), "mast.j2k: Saved", "\"picture\":true", "path");
        CHECK(saved.find(folderB + "/received/") == 0);
        CHECK(contents(saved) == coded);
        const std::vector<uint8_t> again = b.client.pictureBytes("p:" + saved, now);
        CHECK(again == coded);
        CHECK(android::decodePicture(again.data(), again.size(), 2048, error).size() == shown.size());
        // The sender's line follows once the far end has said it is saved.
        CHECK(run(air, both, [&] { return field(a.client.snapshot(now), "mast.j2k: Sent", "\"picture\":true", "path") == mast; }));
        // Never a file from outside the station's own folder.
        const auto outside = base / "outside.j2k";
        std::ofstream(outside, std::ios::binary).write((const char*)coded.data(), (std::streamsize)coded.size());
        CHECK(b.client.pictureBytes("p:" + outside.string(), now).empty());
        CHECK(b.client.pictureBytes("p:" + folderB + "/received/../../outside.j2k", now).empty());
        CHECK(b.client.pictureBytes("x:1", now).empty() && b.client.pictureBytes("s:nonsense", now).empty());

        // A plain file is still asked about, and is no picture.
        const std::string note = a.import("note.txt", "not a picture\n");
        CHECK(a.client.action("file-offer", std::string("TESTB") + '\0' + note, now));
        CHECK(run(air, both, [&] { return b.row("note.txt", "s").flag("receive"); }));
        CHECK(!b.row("note.txt", "s").flag("picture"));
        CHECK(b.client.action("file-decline", b.row("note.txt", "s").text("key"), now));

        CHECK(a.client.action("leave", "", now));
        CHECK(run(air, both, [&] { return a.has("active", "false") && b.has("active", "false"); }));

        // A picture on D2G: taken without asking too, and shown from its key.
        const std::string hill = a.import("hill.j2k", std::string(coded.begin(), coded.end()));
        CHECK(a.client.action("picture-broadcast", hill, now));
        CHECK(run(air, both, [&] { return b.row("hill.j2k", "bh").flag("checked"); }, 20000));
        CHECK(b.row("hill.j2k", "bh").flag("picture"));
        const std::string key = field(b.client.snapshot(now), "hill.j2k", "\"picture\":true", "file");
        const std::string path = field(b.client.snapshot(now), "hill.j2k", "\"picture\":true", "path");
        CHECK(!key.empty() || !path.empty());
        const std::vector<uint8_t> heard = b.client.pictureBytes(key.empty() ? "p:" + path : key, now);
        CHECK(heard == coded);
    }
    std::filesystem::remove_all(base);
    return ragtime_test::finish();
}
