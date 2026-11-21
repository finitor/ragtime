#include "AndroidClient.h"

#include <filesystem>
#include <fstream>
#include <sstream>
#include <stdexcept>

#include "ragtime/protocol/Fields.h"

namespace ragtime::android
{
namespace
{
// JSON strings keep UTF-8 intact, escaping only JSON's ASCII controls.
std::string quoted(const std::string& value)
{
    std::string out = "\"";
    constexpr char hex[] = "0123456789abcdef";
    for (unsigned char c : value)
    {
        if (c == '"' || c == '\\') { out += '\\'; out += (char)c; }
        else if (c < 32) { out += "\\u00"; out += hex[c >> 4]; out += hex[c & 15]; }
        else out += (char)c;
    }
    return out + '"';
}
const char* boolean(bool value) { return value ? "true" : "false"; }
std::string checkedCall(const std::string& value)
{
    auto call = protocol::normalCallsign(value);
    if (call.empty()) throw std::invalid_argument("Enter a callsign of 1–10 letters, digits, / or -");
    return call;
}
} // namespace

AndroidClient::AndroidClient(const std::string& call, const std::string& locator,
                             const std::string& directory, uint64_t now, const std::string& version)
    : link_({checkedCall(call), true, false, link::HostLink::RAGCHEW_IDLE_TIMEOUT_S}), broadcast_(link_),
      stations_(), seals_(link_, broadcast_), broadcastFiles_(link_, broadcast_, seals_), session_(link_),
      fileTraffic_(sessionFiles_, fileHistory_), directory_(directory), now_(now)
{
    if (directory.empty()) throw std::invalid_argument("An app-private storage directory is required");
    std::filesystem::create_directories(directory_);
    std::ifstream(directory_ + "/number") >> savedNumber_;
    if (savedNumber_ > 0xffffff) savedNumber_ = 0;
    broadcast_.setSettings({link_.settings().callsign, locator, true, "Ragtime Android " + version, false,
                           protocol::TAKES_TEXT | protocol::TAKES_STATUS | protocol::TAKES_SEALS | protocol::TAKES_PINGS |
                           protocol::TAKES_SESSION_FILES | protocol::TAKES_BROADCAST_FILES}, savedNumber_);
    broadcastLog_.setPath(directory_ + "/broadcast.log");
    broadcast_.setLog(&broadcastLog_, now);
    session_.setSettings({link_.settings().callsign, locator, true, "Ragtime Android " + version,
                         protocol::TAKES_TEXT | protocol::TAKES_STATUS | protocol::TAKES_SESSION_FILES});
    sessionLog_.setFolder(directory_ + "/sessions");
    session_.setLog(&sessionLog_);
    session_.setTraffic(&fileTraffic_);
    loadFileState(now);
    broadcastFiles_.setPartsFolder(directory_ + "/broadcast-parts", now);
    stations_.setMyCallsign(link_.settings().callsign);
    stations_.setHome(locator);
    // Match desktop's default: unsealed outgoing chat, incoming seals repaired.
    seals_.setSealText(false);
    broadcast_.onItems([this](const auto& records, uint64_t burst, const auto& mode, uint64_t at) {
        seals_.going(records, burst, mode, at);
    });
}
AndroidClient::~AndroidClient()
{
    // Destructors must not allow filesystem failures to cross JNI.
    try {
        disconnected(now_); sessionFiles_.stopAll("Ragtime was closed");
        fileTraffic_.update(now_); collectFiles(now_); saveFileState();
        broadcastFiles_.saveParts(now_); broadcast_.closeLog(now_); saveNumber();
    } catch (...) {}
}
void AndroidClient::connected(uint64_t now) { now_ = now; link_.commandUp(now); events(now); }
void AndroidClient::disconnected(uint64_t now)
{
    now_ = now;
    link_.commandDown(now);
    session_.dataPort(false, now);
    events(now);
    link_.takeCommandOutput();
    link_.takeKissOutput();
    session_.takeDataOutput(now);
    saveNumber();
}
void AndroidClient::dataPort(bool up, uint64_t now) { now_ = now; session_.dataPort(up, now); events(now); }
void AndroidClient::receive(int port, const std::vector<uint8_t>& bytes, uint64_t now)
{
    now_ = now;
    if (port == 0) link_.kissBytes(bytes.data(), bytes.size(), now);
    else if (port == 1) link_.commandBytes((const char*)bytes.data(), bytes.size(), now);
    else if (port == 2)
    {
        link_.sessionHeard(now);
        for (const auto& record : session_.dataBytes(bytes.data(), bytes.size(), now)) stations_.onRecord(record, now);
    }
    else throw std::invalid_argument("Unknown host port");
    events(now);
}
std::vector<uint8_t> AndroidClient::output(int port, uint64_t now)
{
    if (port == 0) return link_.takeKissOutput();
    if (port == 1) { auto text = link_.takeCommandOutput(); return {text.begin(), text.end()}; }
    if (port == 2) return session_.takeDataOutput(now);
    throw std::invalid_argument("Unknown host port");
}
void AndroidClient::events(uint64_t now)
{
    for (const auto& event : link_.takeEvents())
    {
        session_.onEvent(event, now);
        seals_.onEvent(event, now);
        broadcastFiles_.onEvent(event, now);
        using Type = link::HostLink::Event::Type;
        if (event.type == Type::HeardFrom) stations_.heard(event.text, now, event.snr);
        if (event.type == Type::SessionSnr) stations_.heard(event.peer, now, event.snr);
        for (const auto& record : broadcast_.onEvent(event, now))
        {
            stations_.onRecord(record, now);
            if (event.type == Type::Heard && event.handRef) stations_.heard(record.header.callsign, now, event.handRef->snr);
            seals_.onRecord(record, now);
            broadcastFiles_.onRecord(record, now);
        }
        for (const auto& hand : broadcast_.takeHandCheckIns())
        {
            stations_.handCheckIn(hand.call, hand.locator, hand.acceptsSessions, hand.automatic, now);
            stations_.heard(hand.call, now, hand.snr);
        }
    }
    for (auto rebuilt = seals_.takeRebuilt(); !rebuilt.empty(); rebuilt = seals_.takeRebuilt())
        for (const auto& record : rebuilt) {
            broadcast_.onRebuilt(record, now); stations_.onRecord(record, now);
            seals_.onRecord(record, now); broadcastFiles_.onRecord(record, now);
        }
    for (const auto& event : session_.takeEvents())
    {
        using Type = session::SessionChat::Event::Type;
        if (event.type == Type::Calling) stations_.setPartner(event.peer, false, now);
        if (event.type == Type::Started)
        {
            sessionFileLines_.clear(); // the log just read has them
            stations_.setPartner(event.peer, true, now);
            broadcast_.announce(protocol::Status::State::Qrl, event.peer, "", now);
        }
        if (event.type == Type::Ended)
        {
            stations_.setPartner("", false, now);
            broadcast_.announce(protocol::Status::State::Qrv, "", "", now);
        }
    }
    fileTraffic_.setMode(broadcast::findMode(link_.modes(), session_.mode()));
    fileTraffic_.setCuts(link_.data2g());
    collectFiles(now); saveFileState(); saveNumber();
}
void AndroidClient::tick(uint64_t now)
{
    now_ = now;
    link_.tick(now); broadcast_.tick(now); session_.tick(now); seals_.tick(now);
    broadcastFiles_.tick(now); fileTraffic_.tick(now); events(now);
}
bool AndroidClient::action(const std::string& name, const std::string& value, uint64_t now)
{
    now_ = now;
    bool ok = false;
    if (name.compare(0, 5, "file-") == 0) ok = fileAction(name, value, now);
    else if (name == "broadcast") ok = link_.groupOpen() && !broadcast_.send(value, "", now).empty();
    else if (name == "session") ok = session_.send(value, now) != 0;
    else if (name == "call") ok = session_.call(checkedCall(value), now);
    else if (name == "leave") ok = session_.leave(now);
    else if (name == "abort") ok = session_.leaveNow(now);
    else if (name == "pause") { link_.setPaused(value == "true", now); session_.setPaused(value == "true", now); ok = true; }
    else if (name == "narrow") { link_.setNarrow(value == "true", now); ok = true; }
    else if (name == "checkin") ok = link_.groupOpen() && broadcast_.checkIn("", now);
    else throw std::invalid_argument("Unknown action");
    events(now);
    return ok;
}
void AndroidClient::saveNumber()
{
    if (savedNumber_ == broadcast_.newestItem()) return;
    // Reserve numbering before output is taken: a restart cannot reuse an aired item.
    auto temporary = directory_ + "/number.tmp";
    std::ofstream out(temporary, std::ios::trunc);
    out << broadcast_.newestItem() << '\n';
    out.close();
    if (!out) throw std::runtime_error("Cannot save broadcast numbering");
    std::filesystem::rename(temporary, directory_ + "/number");
    savedNumber_ = broadcast_.newestItem();
}
std::string AndroidClient::snapshot(uint64_t now) const
{
    std::ostringstream out;
    out << "{\"ready\":" << boolean(link_.groupOpen()) << ",\"ptt\":" << boolean(link_.ptt())
        << ",\"busy\":" << boolean(link_.busy()) << ",\"paused\":" << boolean(link_.paused())
        << ",\"narrow\":" << boolean(link_.settings().narrow) << ",\"dataUp\":" << boolean(session_.dataUp())
        << ",\"mode\":" << quoted(link_.lastDataMode().empty() ? link_.groupMode() : link_.lastDataMode())
        << ",\"channel\":" << quoted(link::channelLine(link_)) << ",\"peer\":" << quoted(session_.peer())
        << ",\"active\":" << boolean(session_.active()) << ",\"phase\":" << (int)session_.phase()
        << ",\"sessionStatus\":" << quoted(session_.strip(now).text) << ",\"broadcast\":[";
    bool first = true;
    for (const auto& m : broadcast_.messages())
    {
        if (!first) out << ',';
        first = false;
        std::string text = m.text;
        if (m.kind == broadcast::Message::Kind::Ping) text = broadcast::pingLine(m);
        if (m.kind == broadcast::Message::Kind::RollCall) text = broadcast::rollCallLine(m);
        out << "{\"id\":" << m.id << ",\"from\":" << quoted(m.from) << ",\"text\":" << quoted(text)
            << ",\"to\":" << quoted(m.to) << ",\"ours\":" << boolean(m.ours) << ",\"time\":" << m.atMs
            << ",\"state\":" << quoted(m.kind == broadcast::Message::Kind::File ? m.outcome : broadcast::stateLabel(m.state)) << ",\"kind\":" << (int)m.kind << '}';
    }
    out << "],\"session\":["; first = true;
    auto sessionRow = [&](const session::Message& m) {
        if (!first) out << ',';
        first = false;
        out << "{\"id\":" << m.id << ",\"from\":" << quoted(m.from) << ",\"text\":" << quoted(m.text)
            << ",\"ours\":" << boolean(m.ours) << ",\"time\":" << m.atMs
            << ",\"state\":" << quoted(m.kind == session::Message::Kind::File ? std::string() : session::stateLabel(m.state))
            << ",\"kind\":" << (int)m.kind << '}';
    };
    // This ragchew's finished files go in among its lines, by time.
    auto file = sessionFileLines_.begin();
    for (const auto& m : session_.messages())
    {
        for (; file != sessionFileLines_.end() && file->atMs < m.atMs; ++file) sessionRow(*file);
        sessionRow(m);
    }
    for (; file != sessionFileLines_.end(); ++file) sessionRow(*file);
    out << "],\"stations\":["; first = true;
    for (const auto* s : stations_.sorted(broadcast::Column::Heard, true, now))
    {
        if (!first) out << ',';
        first = false;
        out << "{\"call\":" << quoted(s->call) << ",\"where\":" << quoted(stations_.whereLine(*s))
            << ",\"status\":" << quoted(stations_.cell(*s, broadcast::Column::Status, now))
            << ",\"heard\":" << quoted(broadcast::heardAgo(s->lastHeardMs, now))
            << ",\"snr\":" << quoted(stations_.cell(*s, broadcast::Column::Snr, now)) << '}';
    }
    return out.str() + "],\"files\":" + fileSnapshot(now) + "}";
}
} // namespace ragtime::android
