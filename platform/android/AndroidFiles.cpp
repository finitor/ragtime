#include "AndroidClient.h"

#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <sstream>
#include <stdexcept>

namespace ragtime::android {
namespace {
std::string quote(const std::string& s) {
    std::string out = "\"";
    const char* hex = "0123456789abcdef";
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { out += '\\'; out += (char)c; }
        else if (c < 32) { out += "\\u00"; out += hex[c >> 4]; out += hex[c & 15]; }
        else out += (char)c;
    }
    return out + '"';
}
const char* yes(bool value) { return value ? "true" : "false"; }
void atomicText(const std::string& path, const std::string& text) {
    std::ofstream out(path + ".tmp", std::ios::binary | std::ios::trunc);
    out.write(text.data(), (std::streamsize)text.size()); out.close();
    if (!out) throw std::runtime_error("Cannot save file transfer history");
    std::filesystem::rename(path + ".tmp", path);
}
}

void AndroidClient::loadFileState(uint64_t now) {
    std::ifstream in(directory_ + "/files.log", std::ios::binary);
    if (in) {
        std::string text((std::istreambuf_iterator<char>(in)), {});
        std::vector<files::KeptPart> kept;
        if (!files::loadFilesText(text, fileHistory_, kept, &fileLogOther_))
            throw std::runtime_error("Cannot read file transfer history");
        sessionFiles_.restoreKept(kept, now);
    }
    std::filesystem::create_directories(directory_ + "/received");
    std::ifstream chainFile(directory_ + "/seal-chain");
    broadcast::Seals::Chain chain;
    unsigned number = 0, byte = 0;
    if (chainFile >> number && number <= 65535) {
        chain.number = (uint16_t)number;
        for (auto& b : chain.digest) { if (!(chainFile >> byte) || byte > 255) return; b = (uint8_t)byte; }
        seals_.setChain(link_.settings().callsign, chain);
    }
}

void AndroidClient::saveFileState() {
    const auto text = files::saveFilesText(fileHistory_, sessionFiles_.keptParts(), fileLogOther_);
    const auto chain = seals_.chain();
    std::ostringstream seal;
    seal << chain.number;
    for (auto b : chain.digest) seal << ' ' << (unsigned)b;
    const auto signature = text + '\0' + seal.str();
    if (signature == savedFileState_) return;
    atomicText(directory_ + "/files.log", text);
    atomicText(directory_ + "/seal-chain", seal.str());
    savedFileState_ = signature;
}

void AndroidClient::collectFiles(uint64_t now) {
    for (const auto& e : fileTraffic_.takeEvents()) {
        if (e.type != files::FileTraffic::Event::Type::Finished) continue;
        const auto& f = e.finished;
        session::Message line;
        line.kind = session::Message::Kind::File; line.ours = f.outgoing;
        line.from = f.outgoing ? link_.settings().callsign : f.peer;
        line.atMs = f.atMs; line.text = f.name + ": " + files::resultLabel(f);
        line.path = f.path; line.checked = files::checkedWhole(f) && !e.partKept;
        line.picture = f.picture; line.voice = f.voice;
        sessionLog_.file(f.peer, line);
        // Above every id SessionChat gives out, so the list's keys stay unique.
        line.id = (uint64_t(1) << 62) + sessionFileLines_.size();
        if (f.peer == session_.peer()) sessionFileLines_.push_back(line);
    }
    for (const auto& e : broadcastFiles_.takeEvents()) {
        broadcast::BroadcastFile f;
        if (!broadcastFiles_.find(e.id, now, f)) continue;
        using Type = broadcast::BroadcastFiles::Event::Type;
        if (e.type == Type::Announced || e.type == Type::Sending)
            broadcast_.addFile(f.id, f.outgoing, f.from, f.name, now);
        else if (e.type == Type::Saved)
            broadcast_.fileEnded(f.id, "Saved", f.path, f.picture, f.voice);
        else if (e.type == Type::Ended)
            broadcast_.fileEnded(f.id, broadcast::stateLabel(f.state), f.outgoing ? f.path : std::string(), f.picture, f.voice);
        else broadcast_.fileChanged();
    }
    for (auto& f : broadcastFiles_.takeFinished()) fileHistory_.add(std::move(f));
}

bool AndroidClient::fileAction(const std::string& action, const std::string& value, uint64_t now) {
    std::string error;
    // picture-*: the file is an inline picture's codestream (kind 0x01).
    const bool picture = action == "picture-broadcast" || action == "picture-offer";
    const bool offer = action == "file-offer" || action == "picture-offer";
    if (action == "file-broadcast" || offer || picture) {
        std::string path = value, peer;
        if (offer) {
            auto split = value.find('\0');
            if (split == std::string::npos) throw std::invalid_argument("Missing file recipient");
            peer = value.substr(0, split); path = value.substr(split + 1);
            if (session_.phase() != session::SessionChat::Phase::Connected || session_.peer() != peer)
                throw std::runtime_error("Call the selected station before sending a file");
        } else if (!link_.groupOpen()) return false;
        const auto base = std::filesystem::weakly_canonical(directory_);
        const auto source = std::filesystem::weakly_canonical(files::pathFromUtf8(path));
        const auto relative = source.lexically_relative(base);
        if (relative.empty() || *relative.begin() == ".." || !std::filesystem::is_regular_file(source))
            throw std::invalid_argument("Choose an app-private imported file");
        auto id = offer ? sessionFiles_.offer(peer, path, now, error, picture)
                        : broadcastFiles_.send(path, broadcastMode(), now, error, picture);
        if (!id && !error.empty()) throw std::runtime_error(error);
        fileTraffic_.update(now);
        return id != 0;
    }
    if (value.size() < 3 || value[1] != ':') throw std::invalid_argument("Invalid file reference");
    size_t used = 0;
    const auto id = std::stoull(value.substr(2), &used);
    if (used != value.size() - 2) throw std::invalid_argument("Invalid file reference");
    bool ok = false;
    if (value[0] == 's') {
        const auto* t = sessionFiles_.transfer(id);
        if (!t) return false;
        if (action == "file-receive") {
            auto path = t->keptPath;
            if (path.empty()) path = files::utf8FromPath(files::freeSavePath(files::pathFromUtf8(directory_ + "/received"), files::safeFileName(t->name)));
            ok = !path.empty() && sessionFiles_.accept(id, path, error);
        } else if (action == "file-decline") ok = sessionFiles_.decline(id);
        else if (action == "file-cancel") ok = sessionFiles_.cancel(id);
    } else if (value[0] == 'b') {
        broadcast::BroadcastFile f;
        if (!broadcastFiles_.find(id, now, f)) return false;
        if (action == "file-receive") {
            auto path = f.path;
            if (path.empty()) path = files::utf8FromPath(files::freeSavePath(files::pathFromUtf8(directory_ + "/received"), files::safeFileName(f.name)));
            ok = !path.empty() && broadcastFiles_.receive(id, path, now, error);
        } else if (action == "file-decline") ok = broadcastFiles_.ignore(id);
        else if (action == "file-cancel") ok = f.outgoing ? broadcastFiles_.cancel(id) : broadcastFiles_.stop(id, now);
    } else if (value[0] == 'k') {
        if (action == "file-forget") ok = sessionFiles_.deleteKept(id);
        else if (action == "file-resume") {
            for (const auto& p : sessionFiles_.keptParts())
                if (p.id == id && p.outgoing && session_.phase() == session::SessionChat::Phase::Connected && session_.peer() == p.peer)
                    ok = sessionFiles_.offer(p.peer, p.path, now, error) != 0;
        }
    } else if (value[0] == 'h' && action == "file-forget") ok = fileHistory_.remove(id);
    if (!ok && !error.empty()) throw std::runtime_error(error);
    fileTraffic_.update(now);
    return ok;
}

std::string AndroidClient::fileSnapshot(uint64_t now) const {
    std::ostringstream out;
    out << '[';
    bool first = true;
    auto add = [&](const std::string& key, const std::string& via, const std::string& name,
                   const std::string& peer, const std::string& state, const std::string& path,
                   uint64_t size, uint64_t done, uint64_t at, bool ours, bool live, bool checked,
                   bool receive, bool decline, bool cancel, bool resume, bool forget, const std::string& error,
                   bool picture) {
        if (!first) out << ','; first = false;
        out << "{\"key\":" << quote(key) << ",\"via\":" << quote(via) << ",\"name\":" << quote(name)
            << ",\"peer\":" << quote(peer) << ",\"state\":" << quote(state) << ",\"path\":" << quote(path)
            << ",\"size\":" << size << ",\"done\":" << done << ",\"time\":" << at
            << ",\"ours\":" << yes(ours) << ",\"live\":" << yes(live) << ",\"checked\":" << yes(checked)
            << ",\"receive\":" << yes(receive) << ",\"decline\":" << yes(decline) << ",\"cancel\":" << yes(cancel)
            << ",\"resume\":" << yes(resume) << ",\"forget\":" << yes(forget) << ",\"error\":" << quote(error)
            << ",\"picture\":" << yes(picture) << '}';
    };
    for (const auto& r : fileTraffic_.rows(now)) {
        const auto* t = sessionFiles_.transfer(r.id);
        const bool kept = r.look == files::FileRow::Look::Kept;
        const bool asking = !kept && t && t->state == files::FileTransfer::State::Asking;
        add(std::string(kept ? "k:" : "s:") + std::to_string(r.id), "Session", r.name, r.peer, r.state,
            "", r.size, r.done, r.atMs, r.outgoing, !kept, false, asking, asking, !kept,
            kept && r.outgoing && session_.peer() == r.peer && session_.phase() == session::SessionChat::Phase::Connected,
            kept, t ? t->error : std::string(), t != nullptr && t->picture);
    }
    for (const auto& r : broadcastFiles_.rows(now)) {
        broadcast::BroadcastFile f;
        if (!broadcastFiles_.find(r.id, now, f)) continue;
        using S = broadcast::BroadcastFile::State;
        const bool receive = !f.outgoing && (f.state == S::Heard || f.state == S::Incomplete);
        add("b:" + std::to_string(r.id), "Broadcast", r.name, r.peer, r.state, "", r.size, r.done,
            r.atMs, r.outgoing, true, f.complete, receive, receive,
            f.outgoing || f.state == S::Receiving, false, false, f.error, f.picture);
    }
    for (const auto& f : fileHistory_.entries()) {
        const bool saved = f.result == files::FinishedFile::Result::Saved;
        const bool sent = f.result == files::FinishedFile::Result::Sent;
        add("h:" + std::to_string(f.id), f.via == files::Via::Session ? "Session" : "Broadcast",
            f.name, f.peer, files::resultLabel(f), saved || sent ? f.path : std::string(), f.size,
            saved || sent ? f.size : 0, f.atMs, f.outgoing, false,
            files::checkedWhole(f) || (f.via == files::Via::Broadcast && saved),
            false, false, false, false, true, f.reason, f.picture);
    }
    return out.str() + ']';
}
} // namespace ragtime::android
