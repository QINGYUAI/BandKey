#include "BandKeyCredentialProvider.h"

#include <aclapi.h>
#include <cstdarg>
#include <cstdio>
#include <cstring>

namespace
{
constexpr PCWSTR kLogFile = L"C:\\ProgramData\\BandKey\\cp.log";

// 日志只包含状态码与字段信息，不含用户名或密码。
// 首次创建时显式授予 Users 读取权限，便于普通用户在锁屏排查后直接查看。
void ApplyReadableAcl()
{
    BYTE systemSid[SECURITY_MAX_SID_SIZE] = {};
    BYTE adminsSid[SECURITY_MAX_SID_SIZE] = {};
    BYTE usersSid[SECURITY_MAX_SID_SIZE] = {};
    DWORD systemSize = SECURITY_MAX_SID_SIZE;
    DWORD adminsSize = SECURITY_MAX_SID_SIZE;
    DWORD usersSize = SECURITY_MAX_SID_SIZE;

    PSID system = CreateWellKnownSid(WinLocalSystemSid, nullptr, systemSid, &systemSize) ? systemSid : nullptr;
    PSID admins = CreateWellKnownSid(WinBuiltinAdministratorsSid, nullptr, adminsSid, &adminsSize) ? adminsSid : nullptr;
    PSID users = CreateWellKnownSid(WinBuiltinUsersSid, nullptr, usersSid, &usersSize) ? usersSid : nullptr;
    if (!system || !admins || !users)
    {
        return;
    }

    EXPLICIT_ACCESSW entries[3] = {};
    PSID sids[3] = { system, admins, users };
    const DWORD access[3] = { FILE_ALL_ACCESS, FILE_ALL_ACCESS, FILE_GENERIC_READ };
    for (int i = 0; i < 3; ++i)
    {
        entries[i].grfAccessPermissions = access[i];
        entries[i].grfAccessMode = SET_ACCESS;
        entries[i].grfInheritance = NO_INHERITANCE;
        entries[i].Trustee.TrusteeForm = TRUSTEE_IS_SID;
        entries[i].Trustee.TrusteeType = TRUSTEE_IS_USER;
        entries[i].Trustee.ptstrName = reinterpret_cast<LPWSTR>(sids[i]);
    }

    PACL dacl = nullptr;
    if (SetEntriesInAclW(3, entries, nullptr, &dacl) != ERROR_SUCCESS)
    {
        return;
    }

    SetNamedSecurityInfoW(
        const_cast<LPWSTR>(kLogFile),
        SE_FILE_OBJECT,
        DACL_SECURITY_INFORMATION,
        nullptr,
        nullptr,
        dacl,
        nullptr);
    LocalFree(dacl);
}

// 只允许追加写，避免 LogonUI 中多实例互相截断。
// 日志失败绝不能影响解锁流程，因此所有错误都被忽略。
void AppendLine(const char* line)
{
    WIN32_FILE_ATTRIBUTE_DATA attributes = {};
    bool exists = GetFileAttributesExW(kLogFile, GetFileExInfoStandard, &attributes) != FALSE;
    if (exists && attributes.nFileSizeHigh == 0 && attributes.nFileSizeLow > 1024 * 1024)
    {
        DeleteFileW(kLogFile);
        exists = false;
    }

    const HANDLE file = CreateFileW(
        kLogFile,
        FILE_APPEND_DATA,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        nullptr,
        OPEN_ALWAYS,
        FILE_ATTRIBUTE_NORMAL,
        nullptr);
    if (file == INVALID_HANDLE_VALUE)
    {
        return;
    }

    if (!exists)
    {
        ApplyReadableAcl();
    }

    DWORD written = 0;
    WriteFile(file, line, static_cast<DWORD>(strlen(line)), &written, nullptr);
    CloseHandle(file);
}
}

void CpLog(const char* format, ...)
{
    char message[1024] = {};
    va_list args;
    va_start(args, format);
    _vsnprintf_s(message, sizeof(message), _TRUNCATE, format, args);
    va_end(args);

    SYSTEMTIME local = {};
    GetLocalTime(&local);

    char line[1200] = {};
    _snprintf_s(
        line,
        sizeof(line),
        _TRUNCATE,
        "%04u-%02u-%02u %02u:%02u:%02u.%03u [pid %lu] %s\r\n",
        local.wYear,
        local.wMonth,
        local.wDay,
        local.wHour,
        local.wMinute,
        local.wSecond,
        local.wMilliseconds,
        GetCurrentProcessId(),
        message);

    AppendLine(line);
}
