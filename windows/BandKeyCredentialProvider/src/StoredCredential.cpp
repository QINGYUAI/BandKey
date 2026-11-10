#include "BandKeyCredentialProvider.h"

#include <dpapi.h>
#include <fstream>
#include <iterator>

namespace
{
constexpr PCWSTR kDataDir = L"C:\\ProgramData\\BandKey";
constexpr PCWSTR kUsernameFile = L"C:\\ProgramData\\BandKey\\username.txt";
constexpr PCWSTR kCredentialFile = L"C:\\ProgramData\\BandKey\\credential.dat";
}

bool StoredCredential::Load(std::wstring& username, std::wstring& password)
{
    std::ifstream userFile(kUsernameFile, std::ios::binary);
    if (!userFile)
    {
        return false;
    }

    std::string userBytes((std::istreambuf_iterator<char>(userFile)),
                          std::istreambuf_iterator<char>());
    while (!userBytes.empty() && (userBytes.back() == '\n' || userBytes.back() == '\r'))
    {
        userBytes.pop_back();
    }
    if (userBytes.empty())
    {
        return false;
    }

    const int wideLength = MultiByteToWideChar(CP_UTF8, 0, userBytes.c_str(), -1, nullptr, 0);
    if (wideLength <= 0)
    {
        return false;
    }
    username.resize(wideLength);
    MultiByteToWideChar(CP_UTF8, 0, userBytes.c_str(), -1, username.data(), wideLength);
    if (!username.empty() && username.back() == L'\0')
    {
        username.pop_back();
    }

    std::ifstream credFile(kCredentialFile, std::ios::binary);
    if (!credFile)
    {
        return false;
    }
    std::vector<BYTE> blob((std::istreambuf_iterator<char>(credFile)),
                           std::istreambuf_iterator<char>());
    if (blob.empty())
    {
        return false;
    }

    DATA_BLOB in = { static_cast<DWORD>(blob.size()), blob.data() };
    DATA_BLOB out = {};
    if (!CryptUnprotectData(&in, nullptr, nullptr, nullptr, nullptr,
                            CRYPTPROTECT_LOCAL_MACHINE, &out))
    {
        return false;
    }

    try
    {
        password.assign(reinterpret_cast<wchar_t*>(out.pbData), out.cbData / sizeof(wchar_t));
    }
    catch (...)
    {
        SecureZeroMemory(out.pbData, out.cbData);
        LocalFree(out.pbData);
        throw;
    }

    SecureZeroMemory(out.pbData, out.cbData);
    LocalFree(out.pbData);
    return !password.empty();
}
