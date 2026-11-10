#include "BandKeyCredentialProvider.h"

#include <cwchar>
#include <cstring>
#include <new>
#include <ntsecapi.h>
#include <shlwapi.h>
#include <wincred.h>

namespace
{
// Negotiate 认证包 ID。LogonUI 校验序列化结果时要求该字段是 LsaLookupAuthenticationPackage
// 返回的真实包 ID，填 0 会被判定为参数无效（锁屏提示“参数错误”）。
HRESULT RetrieveNegotiateAuthPackage(ULONG* authPackage)
{
    *authPackage = 0;

    HANDLE lsa = nullptr;
    NTSTATUS status = LsaConnectUntrusted(&lsa);
    if (status < 0)
    {
        return HRESULT_FROM_NT(status);
    }

    constexpr char kNegotiate[] = "Negotiate";
    LSA_STRING name = {};
    name.Buffer = const_cast<PCHAR>(kNegotiate);
    name.Length = static_cast<USHORT>(strlen(kNegotiate));
    name.MaximumLength = static_cast<USHORT>(name.Length + 1);

    ULONG packageId = 0;
    status = LsaLookupAuthenticationPackage(lsa, &name, &packageId);
    LsaDeregisterLogonProcess(lsa);
    if (status < 0)
    {
        return HRESULT_FROM_NT(status);
    }

    *authPackage = packageId;
    return S_OK;
}

const CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR kFieldDescriptors[BKF_COUNT] = {
    { BKF_STATUS, CPFT_LARGE_TEXT, const_cast<PWSTR>(L"电脑钥匙"), GUID_NULL },
    { BKF_SUBMIT, CPFT_SUBMIT_BUTTON, const_cast<PWSTR>(L"解锁"), GUID_NULL },
};

// LogonUI 会用 CoTaskMemFree 释放返回的描述符，必须返回独立副本。
HRESULT CopyFieldDescriptor(
    const CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR& source,
    CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** target)
{
    auto* copy = static_cast<CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR*>(
        CoTaskMemAlloc(sizeof(CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR)));
    if (!copy)
    {
        return E_OUTOFMEMORY;
    }

    *copy = source;
    const HRESULT hr = SHStrDupW(source.pszLabel, &copy->pszLabel);
    if (FAILED(hr))
    {
        CoTaskMemFree(copy);
        return hr;
    }

    *target = copy;
    return S_OK;
}

// CPUS_LOGON / CPUS_UNLOCK_WORKSTATION 下系统可以接受 CredProtect 加密后的密码，
// 官方示例在 unlock 场景也始终加密。加密失败时退回明文，避免直接失败。
HRESULT ProtectPassword(const std::wstring& password, std::wstring& protectedPassword)
{
    DWORD cch = 0;
    PWSTR plain = const_cast<PWSTR>(password.c_str());
    const DWORD plainLength = static_cast<DWORD>(password.size()) + 1;

    if (!CredProtectW(FALSE, plain, plainLength, nullptr, &cch, nullptr))
    {
        const DWORD error = GetLastError();
        if (error != ERROR_INSUFFICIENT_BUFFER || cch == 0)
        {
            return HRESULT_FROM_WIN32(error);
        }
    }

    protectedPassword.resize(cch);
    if (!CredProtectW(FALSE, plain, plainLength, protectedPassword.data(), &cch, nullptr))
    {
        protectedPassword.clear();
        return HRESULT_FROM_WIN32(GetLastError());
    }

    protectedPassword.resize(cch);
    return S_OK;
}
}

// ---------------------------------------------------------------------------
// BandKeyCredentialProvider
// ---------------------------------------------------------------------------
BandKeyCredentialProvider::BandKeyCredentialProvider()
{
    credential_ = new (std::nothrow) BandKeyCredential(*this);
}

BandKeyCredentialProvider::~BandKeyCredentialProvider()
{
    if (credential_)
    {
        credential_->Release();
        credential_ = nullptr;
    }
}

IFACEMETHODIMP BandKeyCredentialProvider::QueryInterface(REFIID riid, void** ppv)
{
    if (riid == __uuidof(IUnknown) || riid == __uuidof(ICredentialProvider))
    {
        *ppv = static_cast<ICredentialProvider*>(this);
        AddRef();
        return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
}

IFACEMETHODIMP_(ULONG) BandKeyCredentialProvider::AddRef()
{
    return ++refCount_;
}

IFACEMETHODIMP_(ULONG) BandKeyCredentialProvider::Release()
{
    const ULONG r = --refCount_;
    if (r == 0)
    {
        delete this;
    }
    return r;
}

IFACEMETHODIMP BandKeyCredentialProvider::SetUsageScenario(CREDENTIAL_PROVIDER_USAGE_SCENARIO cpus, DWORD)
{
    usageScenario_ = cpus;

    const HRESULT hr = RetrieveNegotiateAuthPackage(&authPackage_);
    if (FAILED(hr))
    {
        CpLog("SetUsageScenario cpus=%d authPackage lookup failed hr=0x%08lX", static_cast<int>(cpus), hr);
        authPackage_ = 0;
        authPackageValid_ = false;
        return S_OK;
    }

    authPackageValid_ = true;
    CpLog("SetUsageScenario cpus=%d authPackage=%lu", static_cast<int>(cpus), authPackage_);
    return S_OK;
}

IFACEMETHODIMP BandKeyCredentialProvider::SetSerialization(const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION*)
{
    return E_NOTIMPL;
}

IFACEMETHODIMP BandKeyCredentialProvider::Advise(ICredentialProviderEvents* pcpe, UINT_PTR upAdviseContext)
{
    if (events_)
    {
        events_->Release();
    }
    events_ = pcpe;
    if (events_)
    {
        events_->AddRef();
    }
    adviseContext_ = upAdviseContext;
    return S_OK;
}

IFACEMETHODIMP BandKeyCredentialProvider::UnAdvise()
{
    if (events_)
    {
        events_->Release();
        events_ = nullptr;
    }
    return S_OK;
}

IFACEMETHODIMP BandKeyCredentialProvider::GetFieldDescriptorCount(DWORD* pdwCount)
{
    *pdwCount = BKF_COUNT;
    return S_OK;
}

IFACEMETHODIMP BandKeyCredentialProvider::GetFieldDescriptorAt(
    DWORD dwIndex,
    CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** ppcpfd)
{
    if (dwIndex >= BKF_COUNT || !ppcpfd)
    {
        return E_INVALIDARG;
    }

    return CopyFieldDescriptor(kFieldDescriptors[dwIndex], ppcpfd);
}

IFACEMETHODIMP BandKeyCredentialProvider::GetCredentialCount(DWORD* pdwCount, DWORD* pdwDefault, BOOL* pbAutoLogonWithDefault)
{
    const bool autoLogon = credential_ && credential_->AutoUnlockPending();
    *pdwCount = 1;
    *pdwDefault = 0;
    *pbAutoLogonWithDefault = autoLogon ? TRUE : FALSE;
    CpLog("GetCredentialCount count=1 default=0 autologon=%d", autoLogon ? 1 : 0);
    return S_OK;
}

IFACEMETHODIMP BandKeyCredentialProvider::GetCredentialAt(DWORD dwIndex, ICredentialProviderCredential** ppcpc)
{
    if (dwIndex != 0 || !credential_)
    {
        return E_INVALIDARG;
    }
    *ppcpc = credential_;
    credential_->AddRef();
    return S_OK;
}

void BandKeyCredentialProvider::NotifyCredentialsChanged()
{
    if (events_)
    {
        events_->CredentialsChanged(adviseContext_);
    }
}

// ---------------------------------------------------------------------------
// BandKeyCredential
// ---------------------------------------------------------------------------
BandKeyCredential::BandKeyCredential(BandKeyCredentialProvider& provider) : provider_(provider)
{
}

BandKeyCredential::~BandKeyCredential() = default;

IFACEMETHODIMP BandKeyCredential::QueryInterface(REFIID riid, void** ppv)
{
    if (riid == __uuidof(IUnknown) || riid == __uuidof(ICredentialProviderCredential))
    {
        *ppv = static_cast<ICredentialProviderCredential*>(this);
        AddRef();
        return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
}

IFACEMETHODIMP_(ULONG) BandKeyCredential::AddRef()
{
    return ++refCount_;
}

IFACEMETHODIMP_(ULONG) BandKeyCredential::Release()
{
    const ULONG r = --refCount_;
    if (r == 0)
    {
        delete this;
    }
    return r;
}

IFACEMETHODIMP BandKeyCredential::Advise(ICredentialProviderCredentialEvents* pcpce)
{
    if (events_)
    {
        events_->Release();
    }
    events_ = pcpce;
    if (events_)
    {
        events_->AddRef();
    }
    pipe_.Start();
    return S_OK;
}

IFACEMETHODIMP BandKeyCredential::UnAdvise()
{
    pipe_.Stop();
    if (events_)
    {
        events_->Release();
        events_ = nullptr;
    }
    return S_OK;
}

IFACEMETHODIMP BandKeyCredential::SetSelected(BOOL* pbAutoLogon)
{
    // 手机已经授权时，LogonUI 选中该 tile 即自动提交，无需用户再点“解锁”。
    *pbAutoLogon = (authorized_ && autoUnlockPending_) ? TRUE : FALSE;
    CpLog("SetSelected autologon=%d", *pbAutoLogon ? 1 : 0);
    return S_OK;
}

IFACEMETHODIMP BandKeyCredential::SetDeselected()
{
    return S_OK;
}

IFACEMETHODIMP BandKeyCredential::GetFieldState(
    DWORD dwFieldID,
    CREDENTIAL_PROVIDER_FIELD_STATE* pcpfs,
    CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE* pcpfis)
{
    if (dwFieldID >= BKF_COUNT || !pcpfs || !pcpfis)
    {
        return E_INVALIDARG;
    }

    switch (dwFieldID)
    {
    case BKF_STATUS:
        *pcpfs = CPFS_DISPLAY_IN_BOTH;
        *pcpfis = CPFIS_NONE;
        return S_OK;

    case BKF_SUBMIT:
        *pcpfs = CPFS_DISPLAY_IN_SELECTED_TILE;
        *pcpfis = (authorized_ && autoUnlockPending_) ? CPFIS_NONE : CPFIS_DISABLED;
        return S_OK;

    default:
        return E_INVALIDARG;
    }
}

IFACEMETHODIMP BandKeyCredential::GetStringValue(DWORD dwFieldID, PWSTR* ppwsz)
{
    if (dwFieldID != BKF_STATUS || !ppwsz)
    {
        return E_INVALIDARG;
    }

    return SHStrDupW(StatusText().c_str(), ppwsz);
}

IFACEMETHODIMP BandKeyCredential::GetBitmapValue(DWORD, HBITMAP*)
{
    return E_INVALIDARG;
}

IFACEMETHODIMP BandKeyCredential::GetCheckboxValue(DWORD, BOOL*, PWSTR*)
{
    return E_INVALIDARG;
}

IFACEMETHODIMP BandKeyCredential::GetSubmitButtonValue(DWORD dwFieldID, DWORD* pdwAdjacentTo)
{
    if (dwFieldID != BKF_SUBMIT || !pdwAdjacentTo)
    {
        return E_INVALIDARG;
    }

    *pdwAdjacentTo = BKF_STATUS;
    return S_OK;
}

IFACEMETHODIMP BandKeyCredential::GetComboBoxValueCount(DWORD, DWORD*, DWORD*)
{
    return E_INVALIDARG;
}

IFACEMETHODIMP BandKeyCredential::GetComboBoxValueAt(DWORD, DWORD, PWSTR*)
{
    return E_INVALIDARG;
}

IFACEMETHODIMP BandKeyCredential::SetStringValue(DWORD, PCWSTR)
{
    return E_INVALIDARG;
}

IFACEMETHODIMP BandKeyCredential::SetCheckboxValue(DWORD, BOOL)
{
    return E_INVALIDARG;
}

IFACEMETHODIMP BandKeyCredential::SetComboBoxSelectedValue(DWORD, DWORD)
{
    return E_INVALIDARG;
}

IFACEMETHODIMP BandKeyCredential::CommandLinkClicked(DWORD)
{
    return E_INVALIDARG;
}

IFACEMETHODIMP BandKeyCredential::GetSerialization(
    CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* pcpgsr,
    CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* pcpcs,
    PWSTR* ppwszOptionalStatusText,
    CREDENTIAL_PROVIDER_STATUS_ICON* pcpsiOptionalStatusIcon)
{
    *pcpgsr = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
    *pcpsiOptionalStatusIcon = CPSI_NONE;
    if (ppwszOptionalStatusText)
    {
        *ppwszOptionalStatusText = nullptr;
    }
    ZeroMemory(pcpcs, sizeof(*pcpcs));

    if (!authorized_)
    {
        CpLog("GetSerialization skipped: not authorized");
        if (ppwszOptionalStatusText)
        {
            SHStrDupW(L"请先在手机上确认解锁", ppwszOptionalStatusText);
        }
        return S_OK;
    }

    authorized_ = false;
    autoUnlockPending_ = false;

    if (!provider_.AuthPackageValid())
    {
        // 认证包查询失败时不能提交，否则 LogonUI 只会显示“参数错误”。
        CpLog("GetSerialization failed: auth package lookup failed");
        *pcpsiOptionalStatusIcon = CPSI_ERROR;
        if (ppwszOptionalStatusText)
        {
            SHStrDupW(L"解锁组件初始化失败，请重启电脑后再试", ppwszOptionalStatusText);
        }
        return S_OK;
    }

    HRESULT hr = BuildSerialization(*pcpcs);
    CpLog(
        "GetSerialization built hr=0x%08lX cb=%lu authPackage=%lu messageType=%lu",
        hr,
        SUCCEEDED(hr) ? pcpcs->cbSerialization : 0,
        provider_.AuthPackage(),
        SUCCEEDED(hr)
            ? static_cast<unsigned long>(
                  reinterpret_cast<KERB_INTERACTIVE_UNLOCK_LOGON*>(pcpcs->rgbSerialization)->Logon.MessageType)
            : 0UL);

    if (SUCCEEDED(hr))
    {
        *pcpgsr = CPGSR_RETURN_CREDENTIAL_FINISHED;
        *pcpsiOptionalStatusIcon = CPSI_SUCCESS;
        return S_OK;
    }

    // 无法读取本地凭据时，主动通知服务端密码/凭据已失效。
    pipe_.SendLine("PASSWORD_FAILED");
    *pcpsiOptionalStatusIcon = CPSI_ERROR;
    if (ppwszOptionalStatusText)
    {
        SHStrDupW(L"无法读取已保存的 Windows 凭据，请在电脑托盘重新设置", ppwszOptionalStatusText);
    }
    return S_OK;
}

IFACEMETHODIMP BandKeyCredential::ReportResult(
    NTSTATUS ntsStatus,
    NTSTATUS ntsSubstatus,
    PWSTR*,
    CREDENTIAL_PROVIDER_STATUS_ICON*)
{
    CpLog(
        "ReportResult status=0x%08lX substatus=0x%08lX",
        static_cast<unsigned long>(ntsStatus),
        static_cast<unsigned long>(ntsSubstatus));

    // STATUS_LOGON_FAILURE：账号或密码错误。只有这种结果才说明保存的密码确实失效，
    // 其他错误（组件未就绪、策略限制等）不应该让服务端丢弃已保存的凭据。
    constexpr NTSTATUS kStatusLogonFailure = static_cast<NTSTATUS>(0xC000006AL);
    if (ntsStatus == kStatusLogonFailure)
    {
        pipe_.SendLine("PASSWORD_FAILED");
        authorized_ = false;
        autoUnlockPending_ = false;
    }

    return S_OK;
}

void BandKeyCredential::OnUnlockReady(bool ready)
{
    unlockReady_ = ready;
    CpLog("OnUnlockReady ready=%d", ready ? 1 : 0);
    if (!ready)
    {
        authorized_ = false;
        autoUnlockPending_ = false;
    }
    RefreshFields();
}

void BandKeyCredential::OnAutoUnlock(long long requestId)
{
    if (requestId <= 0)
    {
        return;
    }

    autoUnlockRequestId_ = requestId;
    autoUnlockPending_ = true;
    authorized_ = true;
    CpLog("OnAutoUnlock requestId=%lld", requestId);

    // 先 ACK 投递结果，再触发重新枚举；服务端据此结束 pending 状态。
    pipe_.SendLine("AUTO_UNLOCK_ACK:" + std::to_string(requestId));
    RefreshFields();
    provider_.NotifyCredentialsChanged();
}

void BandKeyCredential::OnLegacyUnlock()
{
    // 兼容旧版服务的 "UNLOCK" 通知。
    autoUnlockRequestId_ = 0;
    autoUnlockPending_ = true;
    authorized_ = true;
    CpLog("OnLegacyUnlock");
    RefreshFields();
    provider_.NotifyCredentialsChanged();
}

std::wstring BandKeyCredential::StatusText() const
{
    if (authorized_ && autoUnlockPending_)
    {
        return L"手机已授权，正在解锁…";
    }

    return unlockReady_ ? L"等待手机确认解锁" : L"手机未连接";
}

void BandKeyCredential::RefreshFields()
{
    if (!events_)
    {
        return;
    }

    const std::wstring status = StatusText();
    events_->SetFieldString(this, BKF_STATUS, status.c_str());
}

HRESULT BandKeyCredential::BuildSerialization(CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION& cpcs)
{
    StoredCredential stored;
    std::wstring fullUsername;
    std::wstring password;
    if (!stored.Load(fullUsername, password))
    {
        return E_FAIL;
    }

    std::wstring domain;
    std::wstring user = fullUsername;
    const size_t pos = fullUsername.find(L'\\');
    if (pos != std::wstring::npos)
    {
        domain = fullUsername.substr(0, pos);
        user = fullUsername.substr(pos + 1);
    }

    std::wstring packedPassword = password;
    bool passwordProtected = false;
    if (provider_.UsageScenario() != CPUS_CREDUI)
    {
        std::wstring protectedPassword;
        const HRESULT protectHr = ProtectPassword(password, protectedPassword);
        if (SUCCEEDED(protectHr))
        {
            packedPassword = protectedPassword;
            passwordProtected = true;
        }
        else
        {
            CpLog("CredProtect failed hr=0x%08lX, falling back to plaintext", protectHr);
        }
    }

    // Winlogon/LSA 读取的是 "packed" 结构：UNICODE_STRING.Buffer 必须存放相对
    // 缓冲区起始地址的字节偏移，而不是进程内绝对指针；Length/MaximumLength 也
    // 只包含字符串本身，不包含结尾 NUL。否则 LSA 会返回 STATUS_INVALID_PARAMETER。
    const DWORD cbUser = static_cast<DWORD>(user.size() * sizeof(wchar_t));
    const DWORD cbDomain = static_cast<DWORD>(domain.size() * sizeof(wchar_t));
    const DWORD cbPassword =
        static_cast<DWORD>(wcslen(packedPassword.c_str()) * sizeof(wchar_t));

    const DWORD structSize = sizeof(KERB_INTERACTIVE_UNLOCK_LOGON);
    const DWORD total = structSize + cbUser + cbDomain + cbPassword;

    std::lock_guard<std::mutex> lock(serializationMutex_);
    serializationBuffer_.resize(total);
    ZeroMemory(serializationBuffer_.data(), total);

    auto* kiul = reinterpret_cast<KERB_INTERACTIVE_UNLOCK_LOGON*>(serializationBuffer_.data());
    KERB_INTERACTIVE_LOGON* kil = &kiul->Logon;
    switch (provider_.UsageScenario())
    {
    case CPUS_UNLOCK_WORKSTATION:
        kil->MessageType = KerbWorkstationUnlockLogon;
        break;
    case CPUS_CREDUI:
        kil->MessageType = static_cast<KERB_LOGON_SUBMIT_TYPE>(0);
        break;
    default:
        kil->MessageType = KerbInteractiveLogon;
        break;
    }

    BYTE* cursor = serializationBuffer_.data() + structSize;

    CopyMemory(cursor, domain.c_str(), cbDomain);
    kil->LogonDomainName.Buffer =
        reinterpret_cast<PWSTR>(static_cast<uintptr_t>(cursor - serializationBuffer_.data()));
    kil->LogonDomainName.Length = static_cast<USHORT>(cbDomain);
    kil->LogonDomainName.MaximumLength = static_cast<USHORT>(cbDomain);
    cursor += cbDomain;

    CopyMemory(cursor, user.c_str(), cbUser);
    kil->UserName.Buffer =
        reinterpret_cast<PWSTR>(static_cast<uintptr_t>(cursor - serializationBuffer_.data()));
    kil->UserName.Length = static_cast<USHORT>(cbUser);
    kil->UserName.MaximumLength = static_cast<USHORT>(cbUser);
    cursor += cbUser;

    CopyMemory(cursor, packedPassword.c_str(), cbPassword);
    kil->Password.Buffer =
        reinterpret_cast<PWSTR>(static_cast<uintptr_t>(cursor - serializationBuffer_.data()));
    kil->Password.Length = static_cast<USHORT>(cbPassword);
    kil->Password.MaximumLength = static_cast<USHORT>(cbPassword);

    cpcs.clsidCredentialProvider = CLSID_BandKeyCredentialProvider;
    cpcs.ulAuthenticationPackage = provider_.AuthPackage();
    cpcs.cbSerialization = total;
    cpcs.rgbSerialization = static_cast<BYTE*>(CoTaskMemAlloc(total));
    if (!cpcs.rgbSerialization)
    {
        return E_OUTOFMEMORY;
    }
    CopyMemory(cpcs.rgbSerialization, serializationBuffer_.data(), total);
    CpLog(
        "Serialization packed user=%zu domain=%zu passwordChars=%zu protected=%d messageType=%lu total=%lu",
        user.size(),
        domain.size(),
        packedPassword.size(),
        passwordProtected ? 1 : 0,
        static_cast<unsigned long>(kil->MessageType),
        total);
    return S_OK;
}
