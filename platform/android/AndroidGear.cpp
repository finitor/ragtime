// The gearshift for the Android app: the desktop's own (core gearbox), fed
// as MainFrame feeds it. The gears come from the thresholds data2g-host
// gives with MODES (Data2G 0.5.1 on); a host that gives none has no gears,
// and Ragtime sends in the group's mode as the host has it.
#include "AndroidClient.h"

#include <cmath>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <stdexcept>

namespace ragtime::android
{
namespace
{
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
} // namespace

void AndroidClient::loadGear()
{
    // Auto unless the operator has said otherwise: it starts in the most
    // robust gear and shifts up only once a station's S/N is known.
    gearshift_.setAuto(true);
    std::ifstream in(directory_ + "/gear");
    std::string key, value;
    while (in >> key >> value)
    {
        if (key == "auto") gearshift_.setAuto(value == "1");
        else if (key == "path")
        {
            if (auto path = gearbox::pathFromKey(value)) gearshift_.setPath(*path);
        }
        else if (key == "mode") savedGearMode_ = value;
    }
}

void AndroidClient::saveGear()
{
    std::ostringstream text;
    text << "auto " << (gearshift_.isAuto() ? 1 : 0) << "\npath " << gearbox::pathKey(gearshift_.path()) << '\n';
    const std::string mode = gearshift_.chosenMode().empty() ? savedGearMode_ : gearshift_.chosenMode();
    if (!mode.empty()) text << "mode " << mode << '\n';
    if (text.str() == savedGear_) return;
    std::ofstream out(directory_ + "/gear.tmp", std::ios::trunc);
    out << text.str();
    out.close();
    if (!out) return;
    std::error_code ec;
    std::filesystem::rename(directory_ + "/gear.tmp", directory_ + "/gear", ec);
    if (!ec) savedGear_ = text.str();
}

// data2g-host's MODES list, as the gearshift needs it (MainFrame's
// ModesChanged).
void AndroidClient::hostModes()
{
    std::vector<gearbox::HostMode> modes;
    for (const link::ModeInfo& m : link_.modes())
    {
        gearbox::HostMode h;
        h.name = m.name;
        h.widthHz = m.bandwidthHz;
        h.bitsPerSecond = gearbox::payloadBitsPerSecond(m.bytesPerCodeword, m.maxCodewords, m.secondsAtOne, m.secondsAtMax);
        for (const auto& [channel, dB] : m.thresholds)
        {
            if (std::optional<gearbox::Path> path = gearbox::pathFromKey(channel))
            {
                h.threshold[static_cast<int>(*path)] = dB;
                h.hasThresholds = true;
            }
        }
        modes.push_back(h);
    }
    gearshift_.setHostModes(std::move(modes));
    // The gear chosen last time, once the host has shown it has it.
    if (!savedGearMode_.empty() && !gearshift_.ladder().empty())
    {
        const bool wasAuto = gearshift_.isAuto();
        gearshift_.selectMode(savedGearMode_);
        gearshift_.setAuto(wasAuto);
        savedGearMode_.clear();
    }
}

// The weakest station heard lately sets what Auto dares, and the gear in
// effect is what the seals and their repairs go in.
void AndroidClient::updateGear(uint64_t now)
{
    gearshift_.setNarrowOnly(link_.settings().narrow);
    const std::optional<broadcast::PathReport> path = stations_.weakestPath(now);
    gearshift_.setPathSnr(path ? std::optional<double>(path->dB) : std::nullopt, path ? path->bursts : 0,
                          path ? path->call : std::string());
    seals_.setMode(broadcastMode());
    seals_.setLadder(gearshift_.ladder());
}

// The gear's mode, when data2g-host offers it; else the group's mode as it
// stands, rather than a mode the host would refuse.
std::string AndroidClient::broadcastMode() const
{
    const std::optional<gearbox::Gear> gear = gearshift_.current();
    if (!gear) return std::string();
    for (const link::ModeInfo& m : link_.modes())
    {
        if (m.name == gear->mode) return gear->mode;
    }
    return std::string();
}

bool AndroidClient::gearAction(const std::string& action, const std::string& value)
{
    bool ok = false;
    if (action == "gear-auto")
    {
        gearshift_.setAuto(value == "true");
        ok = true;
    }
    else if (action == "gear")
    {
        // By Data2G's name: a gear on the ladder. It turns Auto off.
        for (const gearbox::Gear& gear : gearshift_.ladder())
        {
            if (gear.mode == value) ok = gearshift_.selectMode(value);
        }
    }
    else if (action == "gear-path")
    {
        if (auto path = gearbox::pathFromKey(value))
        {
            gearshift_.setPath(*path);
            ok = true;
        }
    }
    else throw std::invalid_argument("Unknown action");
    if (ok) saveGear();
    return ok;
}

std::string AndroidClient::gearSnapshot() const
{
    std::ostringstream out;
    const std::optional<gearbox::Gear> current = gearshift_.current();
    const std::optional<double> snr = gearshift_.pathSnr();
    out << "{\"auto\":" << boolean(gearshift_.isAuto()) << ",\"path\":" << quoted(gearbox::pathKey(gearshift_.path()))
        << ",\"pathLabel\":" << quoted(gearbox::pathLabel(gearshift_.path()))
        << ",\"hostThresholds\":" << boolean(gearshift_.hostThresholds())
        << ",\"mode\":" << quoted(current ? current->mode : std::string())
        << ",\"label\":" << quoted(current ? gearbox::gearLabel(*current) : std::string())
        << ",\"speed\":" << quoted(current ? gearbox::formatSpeed(current->bitsPerSecond) : std::string())
        << ",\"bps\":" << (current ? std::lround(current->bitsPerSecond) : 0L)
        << ",\"snr\":" << quoted(snr ? gearbox::formatDb(*snr) : std::string())
        << ",\"snrFrom\":" << quoted(gearshift_.snrFrom()) << ",\"snrBursts\":" << gearshift_.snrBursts()
        << ",\"paths\":[";
    bool first = true;
    for (gearbox::Path path : gearbox::ALL_PATHS)
    {
        if (!first) out << ',';
        first = false;
        out << "{\"key\":" << quoted(gearbox::pathKey(path)) << ",\"label\":" << quoted(gearbox::pathLabel(path)) << '}';
    }
    out << "],\"ladder\":[";
    first = true;
    for (const gearbox::Gear& gear : gearshift_.ladder())
    {
        if (!first) out << ',';
        first = false;
        // How far short the weakest path is for this gear, with Auto's margin.
        const double shortBy = snr ? gearbox::dbShort(gear.threshold, *snr) : 0.0;
        out << "{\"mode\":" << quoted(gear.mode) << ",\"label\":" << quoted(gearbox::gearLabel(gear))
            << ",\"speed\":" << quoted(gearbox::formatSpeed(gear.bitsPerSecond))
            << ",\"needs\":" << quoted(gearbox::formatDb(gear.threshold))
            << ",\"perKb\":" << quoted(gearbox::formatDuration(gearbox::secondsPerKilobyte(gear.bitsPerSecond)))
            << ",\"width\":" << gear.widthHz
            << ",\"short\":" << quoted(shortBy > 0.0 ? gearbox::formatDb(shortBy) : std::string())
            << ",\"current\":" << boolean(current && current->mode == gear.mode) << '}';
    }
    out << "]}";
    return out.str();
}
} // namespace ragtime::android
