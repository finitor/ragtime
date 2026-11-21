// Demo mode's stations. They follow the bench's bots (tests/FakeAirMain.cpp)
// but are quicker to check in and to answer, so a newcomer sees a net come
// up and gets a reply within seconds; no voice, fades or reactions.
#include "DemoAir.h"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <random>
#include <stdexcept>
#include <system_error>
#include <vector>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include "FakeHost.h"
#include "ragtime/broadcast/BroadcastChat.h"
#include "ragtime/broadcast/BroadcastFiles.h"
#include "ragtime/broadcast/Seals.h"
#include "ragtime/files/FileTraffic.h"
#include "ragtime/files/SessionFiles.h"
#include "ragtime/link/HostLink.h"
#include "ragtime/protocol/Fields.h"
#include "ragtime/protocol/Records.h"
#include "ragtime/session/SessionChat.h"

namespace ragtime::android
{
namespace
{
using ragtime_test::FakeAir;
using ragtime_test::FakeHost;

struct Profile
{
    const char* call;
    const char* locator;
    bool sessions;
    const char* lines[3];
    const char* name;
    double signalDb;
};

// The demo's stations, strong to weak: all well heard, so the gearshift's
// Auto has a middle gear to pick and a picture's airtime is minutes, not
// the quarter of an hour a station at -11 dB would make it. Nobody holds these callsigns: the
// prefixes are real (two American, two European) and the suffixes are Q
// signals, which the FCC never assigns (QRA to QUZ, the ARRL's vanity call
// sign notes) and other administrations keep clear of. The names are made
// up too. android/.../DemoGuide.kt names the first two.
const Profile PROFILES[] = {
    {"W1QSO", "FN42", true, {"QST de W1QSO: D2G net bulletin at the top of the hour", "CQ CQ de W1QSO", "QRV, 73"},
     "Walt", 15.0},
    {"K7QTH", "CN87", true, {"Testing the new polar modes tonight", "CQ D2G de K7QTH", "Band is open to EU"}, "Kay",
     10.0},
    {"DL3QRM", "JO62", true, {"Good evening from Berlin", "CQ CQ de DL3QRM", "Greyline path is up"}, "Dieter", 6.0},
    {"F5QRN", "JN18", false, {"Greetings from Paris, 73", "CQ de F5QRN", "Anyone hear me on 20 m?"}, "Luc", 3.0},
};
constexpr size_t STATIONS = sizeof(PROFILES) / sizeof(PROFILES[0]);

// What a station says in a ragchew, in turn.
const char* const RAGCHEW[] = {
    "Copy that, fine business.",
    "Conditions are up and down here, some QSB on your signal.",
    "Rig here is 50 watts into a dipole at 10 m.",
    "WX here is clear and cool tonight.",
    "Solid copy now. Anything else for me?",
};

bool says73(const std::string& text) { return text.find("73") != std::string::npos; }

bool mentionsAFile(std::string text)
{
    for (char& c : text) c = (char)std::tolower((unsigned char)c);
    return text.find("file") != std::string::npos || text.find("again") != std::string::npos
           || text.find("bulletin") != std::string::npos;
}

int connectTo(int port)
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

void sendAll(int fd, const void* bytes, size_t length)
{
    const uint8_t* at = (const uint8_t*)bytes;
    while (fd >= 0 && length > 0)
    {
        ssize_t n = ::send(fd, at, length, MSG_NOSIGNAL);
        if (n <= 0) return;
        at += n;
        length -= (size_t)n;
    }
}

void readSome(int fd, std::vector<uint8_t>& out)
{
    uint8_t buffer[4096];
    for (;;)
    {
        ssize_t n = ::recv(fd, buffer, sizeof(buffer), MSG_DONTWAIT);
        if (n <= 0) return;
        out.insert(out.end(), buffer, buffer + n);
    }
}

void likeDataHost(FakeHost& host, const DemoAir::Options& options, double signalDb)
{
    host.setAirtimeMs(options.airtimeMs);
    host.setSessionRate(options.sessionRate);
    host.setIdleBroadcastMs(3000);
    host.setReportsSnr(true);
    host.setCuts(true);
    host.setThresholds(true);   // so the demo has gears to shift
    host.setReasons(true);
    host.setHands(true);
    host.setHandMs((int)(link::HAND_SECONDS * 1000.0));
    host.setSignalDb(signalDb, 2.0);
}

// One made-up station, wired as the bench's bots are.
struct Station
{
    Station(FakeAir& air, const Profile& p, size_t index, const std::string& folder, const std::string& operatorCall,
            const DemoAir::Options& options, uint64_t startMs, std::mt19937& random)
        : host(air), link({p.call, p.sessions, false, link::HostLink::RAGCHEW_IDLE_TIMEOUT_S}), chat(link),
          seals(link, chat), broadcasts(link, chat, seals), session(link), traffic(files, history), profile(p),
          index(index), dir(std::filesystem::path(folder) / p.call), operatorCall(operatorCall), random(random)
    {
        std::error_code ec;
        std::filesystem::create_directories(dir, ec);
        session.setTraffic(&traffic);
        likeDataHost(host, options, p.signalDb);
        kiss = connectTo(host.kissPort());
        command = connectTo(host.commandPort());
        data = connectTo(host.dataPort());
        if (kiss < 0 || command < 0 || data < 0) throw std::runtime_error("The demo's stations could not start");
        broadcast::BroadcastChat::Settings s;
        s.callsign = p.call;
        s.locator = p.locator;
        s.acceptsSessions = p.sessions;
        s.software = "Ragtime demo station";
        chat.setSettings(s, 0);
        seals.setChain(p.call, broadcast::Seals::Chain());
        seals.setRandomSeed((uint32_t)random());
        chat.onItems([this](const std::vector<protocol::Record>& items, uint64_t burst, const std::string& mode,
                            uint64_t at) { seals.going(items, burst, mode, at); });
        broadcasts.setAutoReceive(files::utf8FromPath(dir));
        session::SessionChat::Settings ss;
        ss.callsign = p.call;
        ss.locator = p.locator;
        ss.acceptsSessions = p.sessions;
        ss.software = "Ragtime demo station";
        session.setSettings(ss);
        session.dataPort(true, startMs);
        link.commandUp(startMs);
        // The net comes up a station at a time, the first within seconds.
        nextMs = startMs + 1500 + 2500 * (uint64_t)index;
    }
    ~Station()
    {
        for (int fd : {kiss, command, data})
        {
            if (fd >= 0) ::close(fd);
        }
    }

    std::string writeBulletin()
    {
        const auto path = dir / (std::string(profile.call) + "-bulletin.txt");
        std::ofstream out(path, std::ios::binary | std::ios::trunc);
        out << "QST de " << profile.call << ": D2G net bulletin\n\n";
        for (int i = 1; i <= 24; i++)
            out << i << ". " << profile.lines[i % 3] << ". Name " << profile.name << ", locator " << profile.locator
                << ", all stations welcome on the D2G net.\n";
        out << "\n73 de " << profile.call << "\n";
        return files::utf8FromPath(path);
    }

    void say(const std::string& text, uint64_t atMs)
    {
        sayText = text;
        sayMs = atMs;
    }

    // A ragchew: answer what the far end says, and leave after its 73.
    void inSession(uint64_t now)
    {
        for (const session::SessionChat::Event& e : session.takeEvents())
        {
            using Type = session::SessionChat::Event::Type;
            if (e.type == Type::Started)
            {
                chat.announce(protocol::Status::State::Qrl, e.peer, "", now);
                say(std::string("Hello ") + e.peer + ", thanks for the call. Name here is " + profile.name + ", QTH "
                        + profile.locator + ". How copy?",
                    now + 2500);
            }
            else if (e.type == Type::Heard)
            {
                const session::Message* m = session.find(e.id);
                if (m == nullptr) continue;
                if (mentionsAFile(m->text))
                {
                    say("Sure, here comes my bulletin.", now + 2500);
                    offerMs = now + 4500;
                }
                else if (says73(m->text))
                {
                    say(std::string("73 ") + e.peer + ", thanks for the ragchew. QRT for now.", now + 3000);
                    leaveMs = now + 8000;
                }
                else
                {
                    say(RAGCHEW[chewed++ % (sizeof(RAGCHEW) / sizeof(RAGCHEW[0]))], now + 3000);
                }
            }
            else if (e.type == Type::Ended)
            {
                chat.announce(protocol::Status::State::Qrv, "", "", now);
                sayText.clear();
                leaveMs = 0;
                offerMs = 0;
            }
        }
        // Files: it saves what it is offered, and says so.
        for (const files::FileTraffic::Event& e : traffic.takeEvents())
        {
            const files::FileTransfer* t = files.transfer(e.id);
            if (t == nullptr) continue;
            if (e.type == files::FileTraffic::Event::Type::Offered && t->state == files::FileTransfer::State::Asking)
            {
                std::string error;
                const std::string path = files::utf8FromPath(files::freeSavePath(dir, t->name));
                if (!files.accept(e.id, t->kept > 0 ? t->keptPath : path, error)) files.decline(e.id);
            }
            else if (e.type == files::FileTraffic::Event::Type::Finished && !t->outgoing
                     && t->state == files::FileTransfer::State::Saved)
            {
                say("Got " + t->name + ", " + files::sizeText(t->size) + ", thanks!", now + 2000);
            }
        }
        const bool connected = session.phase() == session::SessionChat::Phase::Connected;
        if (offerMs != 0 && now >= offerMs && connected)
        {
            std::string error;
            files.offer(session.peer(), writeBulletin(), now, error);
            offerMs = 0;
        }
        if (!sayText.empty() && now >= sayMs && connected)
        {
            session.send(sayText, now);
            sayText.clear();
        }
        if (leaveMs != 0 && now >= leaveMs)
        {
            session.leave(now);
            leaveMs = 0;
        }
    }

    // A short bulletin on D2G, for the Files tab to take.
    void share(uint64_t now)
    {
        const auto path = dir / (std::string(profile.call) + "-net-news-" + std::to_string(++shared) + ".txt");
        std::ofstream out(path, std::ios::binary | std::ios::trunc);
        out << "QST de " << profile.call << ": D2G net news number " << shared << "\n\n";
        for (int i = 0; i < 16; i++)
            out << "Line " << (i + 1) << ": the net meets nightly; check in, pass traffic, and 73 to all.\n";
        out.close();
        std::string error;
        broadcasts.send(files::utf8FromPath(path), "", now, error);
    }

    void pump(uint64_t now)
    {
        std::vector<uint8_t> in;
        readSome(command, in);
        if (!in.empty()) link.commandBytes((const char*)in.data(), in.size(), now);
        in.clear();
        readSome(kiss, in);
        if (!in.empty()) link.kissBytes(in.data(), in.size(), now);
        in.clear();
        readSome(data, in);
        if (!in.empty()) session.dataBytes(in.data(), in.size(), now);
        link.tick(now);
        session.tick(now);
        chat.tick(now);
        seals.tick(now);
        broadcasts.tick(now);
        broadcasts.takeEvents();
        broadcasts.takeFinished();
        traffic.setCuts(link.data2g());
        traffic.tick(now);

        for (const link::HostLink::Event& e : link.takeEvents())
        {
            session.onEvent(e, now);
            seals.onEvent(e, now);
            broadcasts.onEvent(e, now);
            std::vector<protocol::Record> heard = chat.onEvent(e, now);
            for (size_t i = 0; i < heard.size(); i++)
            {
                const protocol::Record r = heard[i];
                seals.onRecord(r, now);
                broadcasts.onRecord(r, now);
                // Lines rebuilt from parity are taken as heard.
                for (const protocol::Record& again : seals.takeRebuilt())
                {
                    chat.onRebuilt(again, now);
                    heard.push_back(again);
                }
                const protocol::Text* text = r.as<protocol::Text>();
                if (text == nullptr || r.header.callsign != operatorCall) continue;
                if (text->to == profile.call)
                {
                    reply = "@" + operatorCall + " copy you fine, thanks. 73 de " + profile.call;
                    replyMs = now + 3500;
                }
                else if (text->to.empty() && r.header.seq % count == index)
                {
                    // One station answers each line said to everyone, in turn.
                    reply = "@" + operatorCall + " good copy on you here in " + profile.locator + ". " + profile.name
                            + " de " + profile.call;
                    replyMs = now + 4500;
                }
            }
        }

        if (link.groupOpen() && now >= nextMs)
        {
            // Its first line checks it in: the Hello, with its locator, rides
            // along. Never a roll call of its own (BroadcastChat::checkIn):
            // each holds the channel for a window of Hands, and a newcomer
            // would wait half a minute for the net to come up. The stations
            // still answer the operator's roll call by Hand.
            if (!checkedIn)
            {
                chat.send(std::string(profile.call) + " checking in to the D2G net. " + profile.name + " here, good evening all.",
                          "", now);
                checkedIn = true;
            }
            else
            {
                chat.send(profile.lines[line++ % 3], "", now);
            }
            nextMs = now + (uint64_t)std::uniform_int_distribution<int>(30000, 80000)(random);
        }
        if (shareEveryMs != 0 && link.groupOpen() && now >= shareMs)
        {
            share(now);
            shareMs = now + shareEveryMs;
        }
        if (!reply.empty() && now >= replyMs && link.groupOpen())
        {
            chat.send(reply, "", now);
            reply.clear();
        }
        inSession(now);

        std::vector<uint8_t> stream = session.takeDataOutput(now);
        sendAll(data, stream.data(), stream.size());
        std::string lines = link.takeCommandOutput();
        sendAll(command, lines.data(), lines.size());
        std::vector<uint8_t> frames = link.takeKissOutput();
        sendAll(kiss, frames.data(), frames.size());
    }

    FakeHost host;
    link::HostLink link;
    broadcast::BroadcastChat chat;
    broadcast::Seals seals;
    broadcast::BroadcastFiles broadcasts;
    session::SessionChat session;
    files::SessionFiles files;
    files::FileHistory history;
    files::FileTraffic traffic;
    const Profile& profile;
    size_t index;
    size_t count = STATIONS; // how many stations are on the demo's air
    std::filesystem::path dir;
    std::string operatorCall;
    std::mt19937& random;
    int kiss = -1, command = -1, data = -1;
    std::string sayText;
    uint64_t sayMs = 0;
    uint64_t leaveMs = 0;
    uint64_t offerMs = 0;
    size_t chewed = 0;
    uint64_t nextMs = 0;
    bool checkedIn = false;
    size_t line = 0;
    std::string reply;
    uint64_t replyMs = 0;
    uint64_t shareEveryMs = 0;
    uint64_t shareMs = 0;
    int shared = 0;
};

uint64_t clockMs()
{
    return (uint64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}
} // namespace

struct DemoAir::Impl
{
    Impl(uint32_t seed) : random(seed), host(air) {}
    // The stations go before the air and their hosts.
    ~Impl() { stations.clear(); }

    std::mt19937 random;
    FakeAir air;
    FakeHost host; // the operator's
    std::vector<std::unique_ptr<Station>> stations;
};

DemoAir::DemoAir(const std::string& folder, const std::string& operatorCall, uint64_t nowMs)
    : DemoAir(folder, operatorCall, nowMs, Options())
{
}

DemoAir::DemoAir(const std::string& folder, const std::string& operatorCall, uint64_t nowMs, const Options& options)
    : impl_(std::make_unique<Impl>(options.seed))
{
    const std::string call = protocol::normalCallsign(operatorCall);
    likeDataHost(impl_->host, options, 5.0);
    for (size_t i = 0; i < STATIONS; i++)
    {
        // The operator's own callsign is never also a demo station's.
        if (call == PROFILES[i].call) continue;
        impl_->stations.push_back(std::make_unique<Station>(impl_->air, PROFILES[i], impl_->stations.size(), folder,
                                                            call, options, nowMs, impl_->random));
    }
    for (auto& station : impl_->stations) station->count = impl_->stations.size();
    // The first station broadcasts a bulletin file soon, and now and then.
    impl_->stations.front()->shareEveryMs = 6 * 60 * 1000;
    impl_->stations.front()->shareMs = nowMs + 40000;
}

DemoAir::~DemoAir() = default;

int DemoAir::kissPort() const { return impl_->host.kissPort(); }
int DemoAir::commandPort() const { return impl_->host.commandPort(); }

void DemoAir::step(uint64_t nowMs, int waitMs)
{
    impl_->air.step(waitMs);
    for (auto& station : impl_->stations) station->pump(nowMs);
}

DemoRunner::DemoRunner(const std::string& folder, const std::string& operatorCall)
    : air_(folder, operatorCall, clockMs())
{
    thread_ = std::thread([this] {
        while (!stopping_.load()) air_.step(clockMs(), 20);
    });
}

DemoRunner::~DemoRunner()
{
    stopping_.store(true);
    if (thread_.joinable()) thread_.join();
}
} // namespace ragtime::android
