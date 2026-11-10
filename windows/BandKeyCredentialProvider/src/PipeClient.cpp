#include "BandKeyCredentialProvider.h"

#include <chrono>
#include <cstdlib>
#include <string>
#include <thread>

namespace
{
constexpr PCWSTR kPipeName = L"\\\\.\\pipe\\BandKeyPipe";
constexpr size_t kMaxLineLength = 8192;
}

PipeClient::PipeClient(BandKeyCredential& owner) : owner_(owner)
{
    stopEvent_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
}

PipeClient::~PipeClient()
{
    Stop();
    if (stopEvent_)
    {
        CloseHandle(stopEvent_);
        stopEvent_ = nullptr;
    }
}

void PipeClient::Start()
{
    if (thread_)
    {
        return;
    }

    thread_ = CreateThread(nullptr, 0,
        [](LPVOID param) -> DWORD
        {
            static_cast<PipeClient*>(param)->Run();
            return 0;
        },
        this, 0, nullptr);
}

void PipeClient::Stop()
{
    if (stopEvent_)
    {
        SetEvent(stopEvent_);
    }

    if (thread_)
    {
        // 阻塞中的 ReadFile 不会响应 stopEvent，取消该线程的同步 I/O 才能及时退出。
        CancelSynchronousIo(thread_);
        WaitForSingleObject(thread_, 3000);
        CloseHandle(thread_);
        thread_ = nullptr;
    }
}

bool PipeClient::SendLine(const std::string& line)
{
    const std::string payload = line + "\n";
    std::lock_guard<std::mutex> lock(writeMutex_);
    if (!pipe_)
    {
        return false;
    }

    DWORD written = 0;
    if (!WriteFile(pipe_, payload.data(), static_cast<DWORD>(payload.size()), &written, nullptr))
    {
        return false;
    }

    return written == payload.size();
}

void PipeClient::HandleLine(const std::string& line)
{
    if (line.empty())
    {
        return;
    }

    if (line == "UNLOCK")
    {
        owner_.OnLegacyUnlock();
        return;
    }

    const std::string readyPrefix = "UNLOCK_READY:";
    if (line.compare(0, readyPrefix.size(), readyPrefix) == 0)
    {
        const std::string value = line.substr(readyPrefix.size());
        owner_.OnUnlockReady(value == "true" || value == "1" || value == "TRUE");
        return;
    }

    const std::string autoPrefix = "AUTO_UNLOCK:";
    if (line.compare(0, autoPrefix.size(), autoPrefix) == 0)
    {
        const long long requestId = _strtoi64(line.c_str() + autoPrefix.size(), nullptr, 10);
        if (requestId > 0)
        {
            owner_.OnAutoUnlock(requestId);
        }
    }
}

void PipeClient::Run()
{
    while (WaitForSingleObject(stopEvent_, 0) == WAIT_TIMEOUT)
    {
        if (!WaitNamedPipeW(kPipeName, 1000))
        {
            continue;
        }

        HANDLE pipe = CreateFileW(kPipeName, GENERIC_READ | GENERIC_WRITE, 0, nullptr, OPEN_EXISTING, 0, nullptr);
        if (pipe == INVALID_HANDLE_VALUE)
        {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            continue;
        }

        {
            std::lock_guard<std::mutex> lock(writeMutex_);
            pipe_ = pipe;
        }

        std::string pending;
        char buffer[256] = {};
        while (WaitForSingleObject(stopEvent_, 0) == WAIT_TIMEOUT)
        {
            DWORD read = 0;
            if (!ReadFile(pipe, buffer, sizeof(buffer), &read, nullptr) || read == 0)
            {
                break;
            }

            pending.append(buffer, read);
            size_t newline = 0;
            while ((newline = pending.find('\n')) != std::string::npos)
            {
                std::string line = pending.substr(0, newline);
                pending.erase(0, newline + 1);
                if (!line.empty() && line.back() == '\r')
                {
                    line.pop_back();
                }

                HandleLine(line);
            }

            if (pending.size() > kMaxLineLength)
            {
                pending.clear();
            }
        }

        {
            std::lock_guard<std::mutex> lock(writeMutex_);
            if (pipe_ == pipe)
            {
                pipe_ = nullptr;
            }
        }

        CloseHandle(pipe);
    }
}
