#ifndef RAGTIME_ANDROID_CLIENT_H
#define RAGTIME_ANDROID_CLIENT_H

#include <string>
#include <vector>

#include "ragtime/broadcast/BroadcastChat.h"
#include "ragtime/broadcast/BroadcastLog.h"
#include "ragtime/broadcast/Seals.h"
#include "ragtime/broadcast/Stations.h"
#include "ragtime/broadcast/BroadcastFiles.h"
#include "ragtime/files/FileTraffic.h"
#include "ragtime/session/SessionChat.h"
#include "ragtime/session/SessionLog.h"

namespace ragtime::android
{
// Socket-free Android adapter. Its owner serializes all calls on one worker.
// UTF-8 crosses JNI as bytes, avoiding JNI modified-UTF-8's emoji corruption.
class AndroidClient
{
public:
    AndroidClient(const std::string& call, const std::string& locator, const std::string& directory, uint64_t now,
                  const std::string& version = "0.1.0");
    ~AndroidClient();
    AndroidClient(const AndroidClient&) = delete;
    AndroidClient& operator=(const AndroidClient&) = delete;

    void connected(uint64_t now);
    void disconnected(uint64_t now);
    void dataPort(bool up, uint64_t now);
    void receive(int port, const std::vector<uint8_t>& bytes, uint64_t now);
    std::vector<uint8_t> output(int port, uint64_t now);
    void tick(uint64_t now);
    bool action(const std::string& name, const std::string& value, uint64_t now);
    std::string snapshot(uint64_t now) const;
    // An inline picture's bytes as they stand, for AndroidPictures to decode
    // off the worker: "s:ID" one in the ragchew, "b:ID" one on D2G (either
    // still arriving, any first part of it the whole picture), or "p:PATH" a
    // finished one, under this station's own folder. Empty if there are none.
    std::vector<uint8_t> pictureBytes(const std::string& key, uint64_t now) const;
    const link::HostLink& link() const { return link_; }

private:
    void events(uint64_t now);
    void saveNumber();
    void loadFileState(uint64_t now);
    void saveFileState();
    void collectFiles(uint64_t now);
    bool fileAction(const std::string& name, const std::string& value, uint64_t now);
    std::string fileSnapshot(uint64_t now) const;
    link::HostLink link_;
    broadcast::BroadcastChat broadcast_;
    broadcast::BroadcastLog broadcastLog_;
    broadcast::Stations stations_;
    broadcast::Seals seals_;
    broadcast::BroadcastFiles broadcastFiles_;
    session::SessionChat session_;
    session::SessionLog sessionLog_;
    files::SessionFiles sessionFiles_;
    files::FileHistory fileHistory_;
    files::FileTraffic fileTraffic_;
    std::string savedFileState_;
    std::vector<std::string> fileLogOther_;
    // This ragchew's finished files, shown in its thread until the next
    // session reads them back from the session log.
    std::vector<session::Message> sessionFileLines_;
    std::string directory_;
    uint32_t savedNumber_ = 0;
    uint64_t now_ = 0;
};
} // namespace ragtime::android
#endif
